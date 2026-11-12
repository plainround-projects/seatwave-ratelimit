package io.plainround.seatwave.ratelimit.limits;

/**
 * What a limiter answers for one request: allowed or refused, how many requests the caller may
 * still make right now, and, when refused, how long until the next one could be allowed.
 *
 * @param isAllowed whether this request may pass
 * @param remaining whole requests still available after this one, 0 or more
 * @param retryAfterMillis 0 when allowed; otherwise the wait until a request could pass, above 0
 */
public record Decision(boolean isAllowed, long remaining, long retryAfterMillis) {

  /** Checks the three rules that make a decision meaningful, so an impossible one cannot exist. */
  public Decision {
    if (remaining < 0) {
      throw new IllegalArgumentException("remaining must be 0 or more, was " + remaining);
    }
    if (isAllowed && retryAfterMillis != 0) {
      throw new IllegalArgumentException("an allowed request has no Retry-After");
    }
    if (!isAllowed && retryAfterMillis <= 0) {
      throw new IllegalArgumentException("a refusal must say how long to wait, above 0 ms");
    }
  }

  /**
   * An allowed request.
   *
   * @param remaining whole requests still available
   * @return the decision
   */
  public static Decision allowed(long remaining) {
    return new Decision(true, remaining, 0);
  }

  /**
   * A refused request.
   *
   * @param retryAfterMillis the wait until a request could pass
   * @return the decision
   */
  public static Decision refused(long retryAfterMillis) {
    return new Decision(false, 0, retryAfterMillis);
  }
}
