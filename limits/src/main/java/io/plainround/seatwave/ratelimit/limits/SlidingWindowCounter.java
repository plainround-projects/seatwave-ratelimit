package io.plainround.seatwave.ratelimit.limits;

import java.time.Duration;

/**
 * A sliding window counter: two fixed-window counts, the current window's and the previous one's,
 * and an estimate of the last full window that weights the previous count by how much of it still
 * overlaps: estimate = previous x (window - elapsed) / window + current. A request is allowed while
 * the estimate, counting it, stays at or under {@code limit}. Two counters per key, like the fixed
 * window, but no boundary burst of twice the limit; the price is that the estimate assumes the
 * previous window's requests were spread evenly, so traffic bunched at the end of a window can get
 * a little more than the limit through.
 */
public final class SlidingWindowCounter implements RateLimiter {

  private final long limit;
  private final long windowMillis;
  private long windowStartMillis = Long.MIN_VALUE;
  private long previousCount;
  private long currentCount;

  /**
   * Creates the counter.
   *
   * @param limit requests allowed per window, 1 or more
   * @param window the window's length, at least 1 ms
   */
  public SlidingWindowCounter(long limit, Duration window) {
    if (limit < 1 || window.toMillis() < 1) {
      throw new IllegalArgumentException(
          "limit must be 1 or more and window at least 1 ms, were " + limit + ", " + window);
    }
    this.limit = limit;
    this.windowMillis = window.toMillis();
  }

  @Override
  public synchronized Decision tryAcquire(long nowMillis) {
    roll(nowMillis);
    long elapsedMillis = nowMillis - windowStartMillis;
    // Everything is multiplied by the window length, so the comparison stays in whole numbers:
    // previous x (window - elapsed) + (current + 1) x window <= limit x window.
    long weightedPrevious = previousCount * (windowMillis - elapsedMillis);
    if (weightedPrevious + (currentCount + 1) * windowMillis <= limit * windowMillis) {
      currentCount++;
      long remainingScaled = limit * windowMillis - weightedPrevious - currentCount * windowMillis;
      return Decision.allowed(remainingScaled / windowMillis);
    }
    return Decision.refused(waitMillis(nowMillis, elapsedMillis));
  }

  // The shortest wait after which the estimate, counting one more request, fits the limit: either
  // later in this window, as the previous window's weight shrinks, or early in the next one, where
  // this window's count becomes the "previous" count.
  private long waitMillis(long nowMillis, long elapsedMillis) {
    long spareNow = limit - currentCount - 1;
    if (spareNow >= 0 && previousCount > 0) {
      long neededElapsed = windowMillis - Math.floorDiv(spareNow * windowMillis, previousCount);
      if (neededElapsed < windowMillis) {
        return Math.max(1, neededElapsed - elapsedMillis);
      }
    }
    long neededInNext =
        currentCount == 0
            ? 0
            : Math.max(0, windowMillis - Math.floorDiv((limit - 1) * windowMillis, currentCount));
    return Math.max(1, windowStartMillis + windowMillis + neededInNext - nowMillis);
  }

  private void roll(long nowMillis) {
    long currentStartMillis = Math.floorDiv(nowMillis, windowMillis) * windowMillis;
    if (currentStartMillis == windowStartMillis) {
      return;
    }
    // One window later, the current count becomes the previous one; more than one window later,
    // both are old news.
    boolean isNextWindow = currentStartMillis - windowStartMillis == windowMillis;
    previousCount = isNextWindow ? currentCount : 0;
    currentCount = 0;
    windowStartMillis = currentStartMillis;
  }
}
