package io.plainround.seatwave.ratelimit.limits;

import java.time.Duration;
import java.util.ArrayDeque;

/**
 * A sliding log: it remembers the time of every allowed request of the last window, and allows a
 * new one only while fewer than {@code limit} are remembered. It is exact (never more than limit in
 * any window of that length), and it pays for that with one stored time per allowed request: a
 * limit of 1,000 per hour stores up to 1,000 times for every key.
 */
public final class SlidingLog implements RateLimiter {

  private final long limit;
  private final long windowMillis;
  private final ArrayDeque<Long> allowedAtMillis = new ArrayDeque<>();

  /**
   * Creates the log.
   *
   * @param limit requests allowed in any window, 1 or more
   * @param window the window's length, at least 1 ms
   */
  public SlidingLog(long limit, Duration window) {
    if (limit < 1 || window.toMillis() < 1) {
      throw new IllegalArgumentException(
          "limit must be 1 or more and window at least 1 ms, were " + limit + ", " + window);
    }
    this.limit = limit;
    this.windowMillis = window.toMillis();
  }

  @Override
  public synchronized Decision tryAcquire(long nowMillis) {
    // Forget every request that is a whole window old: the window is (now - window, now].
    while (!allowedAtMillis.isEmpty() && allowedAtMillis.peekFirst() <= nowMillis - windowMillis) {
      allowedAtMillis.pollFirst();
    }
    if (allowedAtMillis.size() < limit) {
      allowedAtMillis.addLast(nowMillis);
      return Decision.allowed(limit - allowedAtMillis.size());
    }
    // The next request can pass when the oldest remembered one leaves the window.
    return Decision.refused(allowedAtMillis.peekFirst() + windowMillis - nowMillis);
  }

  /**
   * How many times this key stores right now: the log's memory cost.
   *
   * @return the stored times
   */
  public synchronized int storedTimes() {
    return allowedAtMillis.size();
  }
}
