package io.plainround.seatwave.ratelimit.limits;

/**
 * One caller's limit: the state of one limit key (one fan, one client address) under one policy.
 * The time is passed in, never read from a clock inside, so a test or a demonstration can replay
 * the same timeline exactly; in the gateway the time comes from the store (Redis's own clock).
 *
 * <p>Implementations are thread-safe: every method that reads or changes state is synchronized, so
 * a check and the take that follows it are one step.
 */
public interface RateLimiter {

  /**
   * Decides one request and, when it is allowed, counts it.
   *
   * @param nowMillis the time of the request, in milliseconds on one clock that never goes back
   * @return allowed with the remaining count, or refused with the wait
   */
  Decision tryAcquire(long nowMillis);
}
