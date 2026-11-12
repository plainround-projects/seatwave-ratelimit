package io.plainround.seatwave.ratelimit.edge.limiter;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * A stand-in for Redis in tests: it answers every script call with the reply a test set, or throws
 * the failure a test set, and records the keys and arguments of each call. Spring's template is a
 * class the project does not own, so the tests replace its one method the limiter calls rather than
 * run a Redis. Thread-safe.
 */
final class ScriptedRedis extends StringRedisTemplate {

  private final AtomicReference<List<Long>> reply = new AtomicReference<>(List.of(1L, 4L, 0L));
  private final AtomicReference<DataAccessException> failure = new AtomicReference<>();
  private final List<List<String>> calls = new CopyOnWriteArrayList<>();
  private final AtomicReference<Duration> serializedDelay = new AtomicReference<>(Duration.ZERO);
  private final Object connectLock = new Object();

  /**
   * Every later call answers allowed, with this many tokens left.
   *
   * @param remaining the whole tokens left
   */
  void allowWith(long remaining) {
    failure.set(null);
    reply.set(List.of(1L, remaining, 0L));
  }

  /**
   * Every later call answers refused, with this wait.
   *
   * @param retryAfterMillis the wait the script computed
   */
  void refuseFor(long retryAfterMillis) {
    failure.set(null);
    reply.set(List.of(0L, 0L, retryAfterMillis));
  }

  /**
   * Every later call fails as a down or slow Redis does.
   *
   * @param storeFailure the exception the template would throw
   */
  void failWith(DataAccessException storeFailure) {
    failure.set(storeFailure);
  }

  /**
   * Every later call first waits this long under one lock, then fails as a refused connection: the
   * way Spring Data Redis reconnects to a stopped Redis, one attempt after the other.
   *
   * @param delay the time of one connection attempt
   */
  void reconnectOneAtATime(Duration delay) {
    serializedDelay.set(delay);
    failure.set(new RedisConnectionFailureException("Unable to connect to Redis"));
  }

  /**
   * The calls so far, each as the key followed by the three arguments.
   *
   * @return the calls, oldest first
   */
  List<List<String>> calls() {
    return List.copyOf(calls);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T execute(RedisScript<T> script, List<String> keys, Object... arguments) {
    calls.add(Stream.concat(keys.stream(), Arrays.stream(arguments).map(String::valueOf)).toList());
    Duration delay = serializedDelay.get();
    if (delay.isPositive()) {
      synchronized (connectLock) {
        try {
          Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
    DataAccessException storeFailure = failure.get();
    if (storeFailure != null) {
      throw storeFailure;
    }
    // The limiter's script returns a list of whole numbers; the template would give it as T.
    return (T) reply.get();
  }
}
