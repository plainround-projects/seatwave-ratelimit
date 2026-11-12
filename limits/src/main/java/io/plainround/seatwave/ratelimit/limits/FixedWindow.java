package io.plainround.seatwave.ratelimit.limits;

import java.time.Duration;

/**
 * A fixed window counter: time is cut into windows of equal length that start at multiples of the
 * window (10:00:00, 10:01:00, ...), and each window allows at most {@code limit} requests. One
 * counter and one window start per key: the cheapest limiter there is. Its known flaw is the
 * boundary burst: a caller can use a whole window's limit at its end and the next window's limit at
 * the next one's start, which is twice the limit within a moment.
 */
public final class FixedWindow implements RateLimiter {

  private final long limit;
  private final long windowMillis;
  private long windowStartMillis = Long.MIN_VALUE;
  private long countInWindow;

  /**
   * Creates the counter.
   *
   * @param limit requests allowed per window, 1 or more
   * @param window the window's length, at least 1 ms
   */
  public FixedWindow(long limit, Duration window) {
    if (limit < 1 || window.toMillis() < 1) {
      throw new IllegalArgumentException(
          "limit must be 1 or more and window at least 1 ms, were " + limit + ", " + window);
    }
    this.limit = limit;
    this.windowMillis = window.toMillis();
  }

  @Override
  public synchronized Decision tryAcquire(long nowMillis) {
    long currentStartMillis = Math.floorDiv(nowMillis, windowMillis) * windowMillis;
    if (currentStartMillis != windowStartMillis) {
      // A new window forgets everything about the old one: this is where the boundary burst comes
      // from.
      windowStartMillis = currentStartMillis;
      countInWindow = 0;
    }
    if (countInWindow < limit) {
      countInWindow++;
      return Decision.allowed(limit - countInWindow);
    }
    return Decision.refused(windowStartMillis + windowMillis - nowMillis);
  }
}
