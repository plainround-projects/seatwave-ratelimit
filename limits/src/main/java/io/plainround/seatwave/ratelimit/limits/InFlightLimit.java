package io.plainround.seatwave.ratelimit.limits;

import java.util.concurrent.Semaphore;

/**
 * Load shedding by in-flight count: at most {@code maxInFlight} requests are being served at once,
 * and one more is refused at once instead of waiting in a queue. It protects the service as a
 * whole, whoever is sending: unlike a rate limit it knows nothing about callers, only about how
 * busy the service is right now. By Little's Law (Module 4) a limit of 64 with requests of 40 ms
 * caps the service near 64 / 0.040 s = 1,600 requests per second and its waiting at zero.
 *
 * <p>Thread-safe: the semaphore is the only state.
 */
public final class InFlightLimit {

  private final int maxInFlight;
  private final Semaphore permits;

  /**
   * Creates the limit.
   *
   * @param maxInFlight requests allowed in flight at once, 1 or more
   */
  public InFlightLimit(int maxInFlight) {
    if (maxInFlight < 1) {
      throw new IllegalArgumentException("maxInFlight must be 1 or more, was " + maxInFlight);
    }
    this.maxInFlight = maxInFlight;
    this.permits = new Semaphore(maxInFlight);
  }

  /**
   * Admits one request if there is room, without waiting: a request that would have to wait is
   * shed, because waiting is exactly what makes an overloaded service slow for everyone.
   *
   * @return true when admitted; the caller must then call {@link #leave()} exactly once
   */
  public boolean tryEnter() {
    return permits.tryAcquire();
  }

  /** Ends one admitted request. */
  public void leave() {
    permits.release();
  }

  /**
   * The requests being served now.
   *
   * @return the count, from 0 to the limit
   */
  public int inFlight() {
    return maxInFlight - permits.availablePermits();
  }
}
