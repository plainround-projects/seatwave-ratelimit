package io.plainround.seatwave.ratelimit.edge.limiter;

import io.plainround.seatwave.ratelimit.limits.Decision;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The shared token bucket: one call of {@code ratelimit/token-bucket.lua} in Redis per request,
 * which refills, checks and takes in one atomic step. Spring Data Redis sends the script by its
 * SHA-1 digest ({@code EVALSHA}) and sends the source only when Redis does not know it yet, so the
 * script's text crosses the network once per Redis process, not once per request.
 *
 * <p>Every call waits at most {@code callTimeout} for its answer, whatever the client is doing.
 * Lettuce bounds a command by {@code spring.data.redis.timeout}, but while Redis is stopped Spring
 * Data Redis reconnects under one lock, one attempt after the other, so without this bound the
 * hundredth request of a burst waits for a hundred connection timeouts (measured in the lab: about
 * 52 ms each, five seconds for the last of a burst of 100). The calls run on a few platform
 * threads, not on virtual threads: a gateway with one CPU has one carrier thread, and a call queued
 * behind a hundred requests on it would start too late to answer in time.
 *
 * <p>Thread-safe: the template, the script and the executor are shared.
 */
public final class RedisTokenBucket implements AutoCloseable {

  private static final String KEY_PREFIX = "seatwave:rl:";

  // Spring Data Redis returns a Lua table as a List of Long; a class literal cannot say "of Long".
  @SuppressWarnings("unchecked")
  private static final RedisScript<List<Long>> SCRIPT =
      (RedisScript<List<Long>>)
          (RedisScript<?>)
              RedisScript.of(new ClassPathResource("ratelimit/token-bucket.lua"), List.class);

  // Calls in flight are rate x round trip: 1,000 a second at 1 ms is 1, so 16 threads leave room
  // for a slow second; beyond 256 waiting, a call is refused at once as a store failure.
  private static final int CALL_THREADS = 16;
  private static final int MOST_WAITING_CALLS = 256;

  private final StringRedisTemplate redis;
  private final Duration callTimeout;
  private final ThreadPoolExecutor calls =
      new ThreadPoolExecutor(
          CALL_THREADS,
          CALL_THREADS,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(MOST_WAITING_CALLS),
          Thread.ofPlatform().name("rate-limit-store-", 0).daemon(true).factory());

  /**
   * Creates the caller.
   *
   * @param redis Spring Boot's Redis template, with the read timeout of {@code
   *     spring.data.redis.timeout}
   * @param callTimeout the longest a request waits for the store, {@code spring.data.redis.timeout}
   */
  public RedisTokenBucket(StringRedisTemplate redis, Duration callTimeout) {
    this.redis = redis;
    this.callTimeout = callTimeout;
  }

  /**
   * Takes one token from a caller's bucket under a policy.
   *
   * @param policy the limit
   * @param limitKey whose bucket, such as {@code sub:fan-42} or {@code addr:203.0.113.7}
   * @return the decision
   * @throws DataAccessException when Redis is down, refuses, or does not answer within the timeout
   */
  public Decision take(LimitPolicy policy, String limitKey) {
    Future<List<Long>> answer;
    try {
      answer = calls.submit(() -> callScript(policy, limitKey));
    } catch (RejectedExecutionException saturated) {
      throw new QueryTimeoutException("too many rate-limit store calls are waiting", saturated);
    }
    List<Long> reply = await(answer);
    boolean isAllowed = reply.get(0) == 1;
    return isAllowed ? Decision.allowed(reply.get(1)) : Decision.refused(reply.get(2));
  }

  /** Stops the call threads when the gateway shuts down. */
  @Override
  public void close() {
    calls.close();
  }

  private List<Long> callScript(LimitPolicy policy, String limitKey) {
    // One key per bucket, so the script touches one hash slot and works on a Redis Cluster too.
    return redis.execute(
        SCRIPT,
        List.of(KEY_PREFIX + policy.name() + ":" + limitKey),
        Long.toString(policy.capacity()),
        Long.toString(policy.refillTokens()),
        Long.toString(policy.refillPeriod().toMillis()));
  }

  private List<Long> await(Future<List<Long>> answer) {
    try {
      return answer.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException late) {
      // The call goes on in the background and ends on its own timeout; this request moves on.
      answer.cancel(true);
      throw new QueryTimeoutException("Redis did not answer within " + callTimeout, late);
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof DataAccessException storeFailure) {
        throw storeFailure;
      }
      throw new IllegalStateException("the rate-limit script call failed", failed.getCause());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      answer.cancel(true);
      throw new QueryTimeoutException("interrupted while waiting for Redis", interrupted);
    }
  }
}
