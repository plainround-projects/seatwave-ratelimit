package io.plainround.seatwave.ratelimit.edge.limiter;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;

/**
 * One limit: which requests it covers (method and path prefix), whose bucket a request takes from
 * (the verified fan or the client's address), the bucket's size and refill, and what happens when
 * the shared store cannot answer. Bound from {@code seatwave.ratelimit.policies[n]} and checked at
 * start-up with the others, so a wrong limit stops the gateway with a list of every problem.
 *
 * @param name the policy's name, sent to clients in the RateLimit headers and used in the Redis key
 * @param method the HTTP method it covers, or {@code ANY}
 * @param pathPrefix the path prefix it covers; the last policy must be {@code /}
 * @param key whose bucket a request takes from
 * @param capacity the bucket's size, the burst a caller may send at once
 * @param refillTokens tokens that come back per refill period
 * @param refillPeriod the period over which refillTokens come back
 * @param whenStoreDown what to do when Redis does not answer in time
 * @param isShadow when true, refusals are only counted and logged, and every request passes
 */
public record LimitPolicy(
    @NotNull @Pattern(regexp = "[a-z][a-z0-9-]{0,31}") String name,
    @NotNull @Pattern(regexp = "ANY|GET|POST|PUT|PATCH|DELETE") String method,
    @NotNull @Pattern(regexp = "/[A-Za-z0-9/_-]{0,100}") String pathPrefix,
    @NotNull KeySource key,
    @Min(1) @Max(1_000_000) long capacity,
    @Min(1) @Max(1_000_000) long refillTokens,
    @NotNull Duration refillPeriod,
    @NotNull StoreDown whenStoreDown,
    boolean isShadow) {

  // A refill period shorter than a millisecond cannot be expressed in the script's credits.
  private static final Duration SHORTEST_PERIOD = Duration.ofMillis(1);
  private static final Duration LONGEST_PERIOD = Duration.ofDays(1);
  private static final long MILLIS_PER_SECOND = 1_000;

  /** Whose bucket a request takes from. */
  public enum KeySource {
    /** The fan the gateway verified ({@code X-SeatWave-Subject}); without one, the address. */
    SUBJECT,
    /** The client's address, as the trusted balancer reported it in {@code X-Forwarded-For}. */
    ADDRESS
  }

  /** What a request gets when the shared store is down or slow. */
  public enum StoreDown {
    /** Refuse with 503 and Retry-After: for actions where one caller's excess does real harm. */
    REFUSE,
    /** Decide with this instance's own bucket, sized to its share of the limit. */
    LOCAL
  }

  /**
   * The refill period must be from 1 ms to one day.
   *
   * @return true when it is in range or missing (a missing period has its own problem line)
   */
  @AssertTrue(message = "refillPeriod must be from 1ms to 1d")
  public boolean isRefillPeriodInRange() {
    return refillPeriod == null
        || (refillPeriod.compareTo(SHORTEST_PERIOD) >= 0
            && refillPeriod.compareTo(LONGEST_PERIOD) <= 0);
  }

  /**
   * Whether this policy covers a request.
   *
   * @param requestMethod the request's method
   * @param requestPath the request's path, without the query
   * @return true when the method and the path prefix both match
   */
  public boolean covers(String requestMethod, String requestPath) {
    boolean isMethodCovered = "ANY".equals(method) || method.equals(requestMethod);
    // "/v1/holds" covers "/v1/holds" and "/v1/holds/7", never "/v1/holdsx".
    boolean isPathCovered =
        "/".equals(pathPrefix)
            || requestPath.equals(pathPrefix)
            || requestPath.startsWith(pathPrefix + "/");
    return isMethodCovered && isPathCovered;
  }

  /**
   * The seconds a full bucket takes to refill from empty: the window the RateLimit-Policy header
   * names, so "5 per 50 s" for 5 tokens at 1 per 10 s.
   *
   * @return the window in whole seconds, at least 1
   */
  public long windowSeconds() {
    return Math.max(
        1, Math.ceilDiv(capacity * refillPeriod.toMillis(), refillTokens * MILLIS_PER_SECOND));
  }
}
