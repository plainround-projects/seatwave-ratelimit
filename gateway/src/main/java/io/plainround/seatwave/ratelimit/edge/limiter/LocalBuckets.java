package io.plainround.seatwave.ratelimit.edge.limiter;

import io.plainround.seatwave.ratelimit.limits.Decision;
import io.plainround.seatwave.ratelimit.limits.TokenBucket;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This instance's own buckets, used only while the shared store cannot answer and only for policies
 * whose {@code when-store-down} is {@code LOCAL}. Each bucket gets this instance's share of the
 * limit: with 3 instances, a policy of 40 tokens at 20 per second becomes 13 tokens at 20 per 3
 * seconds here, so the three together stay near the shared limit while the balancer spreads a
 * caller's requests evenly.
 *
 * <p>Thread-safe: the map is concurrent and each bucket synchronizes its own state.
 */
public final class LocalBuckets {

  // ponytail: when the map passes this size it is emptied, which briefly forgives every caller on
  // this instance; an LRU cache with expiry is the upgrade if store outages last long.
  private static final int MOST_KEYS = 100_000;

  private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
  private final int gatewayInstances;
  private final Clock clock;

  /**
   * Creates the fallback.
   *
   * @param gatewayInstances how many instances share each limit
   * @param clock this instance's clock; only the fallback reads it, the shared bucket uses Redis's
   */
  public LocalBuckets(int gatewayInstances, Clock clock) {
    this.gatewayInstances = gatewayInstances;
    this.clock = clock;
  }

  /**
   * Takes one token from this instance's share of a caller's bucket.
   *
   * @param policy the limit
   * @param limitKey whose bucket
   * @return the decision
   */
  public Decision take(LimitPolicy policy, String limitKey) {
    if (buckets.size() > MOST_KEYS) {
      buckets.clear();
    }
    long nowMillis = clock.millis();
    TokenBucket bucket =
        buckets.computeIfAbsent(
            policy.name() + ":" + limitKey,
            unused ->
                new TokenBucket(
                    Math.max(1, policy.capacity() / gatewayInstances),
                    policy.refillTokens(),
                    policy.refillPeriod().multipliedBy(gatewayInstances),
                    nowMillis));
    return bucket.tryAcquire(nowMillis);
  }
}
