package io.plainround.seatwave.ratelimit.limits;

import java.time.Duration;

/**
 * A token bucket: it holds at most {@code capacity} tokens, gains {@code refillTokens} every {@code
 * refillPeriod}, spread evenly over time, and every allowed request takes one. A caller can
 * therefore send a burst of up to {@code capacity} at once, and then only as fast as tokens come
 * back. Over any interval of length T it allows at most capacity + rate x T requests.
 *
 * <p>The fill level is kept in whole "credits" so the arithmetic is exact: one token is {@code
 * refillPeriod} in milliseconds worth of credits, and every elapsed millisecond adds {@code
 * refillTokens} credits. Doubles would make 0.2 + 0.8 come out as 0.9999999 and turn a 8,000 ms
 * wait into 8,001 ms.
 */
public final class TokenBucket implements RateLimiter {

  private final long capacityCredits;
  private final long creditsPerToken;
  private final long creditsPerMilli;
  private long credits;
  private long refilledAtMillis;

  /**
   * Creates a full bucket.
   *
   * @param capacity the most tokens the bucket holds, the burst size, 1 or more
   * @param refillTokens tokens added per refill period, 1 or more
   * @param refillPeriod the period over which refillTokens come back, at least 1 ms
   * @param nowMillis the time the bucket is created
   */
  public TokenBucket(long capacity, long refillTokens, Duration refillPeriod, long nowMillis) {
    if (capacity < 1 || refillTokens < 1 || refillPeriod.toMillis() < 1) {
      throw new IllegalArgumentException(
          "capacity and refillTokens must be 1 or more and refillPeriod at least 1 ms, were "
              + capacity
              + ", "
              + refillTokens
              + ", "
              + refillPeriod);
    }
    this.creditsPerToken = refillPeriod.toMillis();
    this.creditsPerMilli = refillTokens;
    this.capacityCredits = Math.multiplyExact(capacity, creditsPerToken);
    this.credits = capacityCredits;
    this.refilledAtMillis = nowMillis;
  }

  @Override
  public synchronized Decision tryAcquire(long nowMillis) {
    refill(nowMillis);
    if (credits >= creditsPerToken) {
      credits -= creditsPerToken;
      return Decision.allowed(credits / creditsPerToken);
    }
    // The wait is exactly the credits still missing for one token, rounded up to a whole ms.
    long missingCredits = creditsPerToken - credits;
    return Decision.refused(Math.ceilDiv(missingCredits, creditsPerMilli));
  }

  /**
   * The tokens in the bucket at a time, as a fraction, for drawing a timeline; it refills first.
   *
   * @param nowMillis the time
   * @return the tokens, from 0 to capacity
   */
  public synchronized double tokens(long nowMillis) {
    refill(nowMillis);
    return (double) credits / creditsPerToken;
  }

  private void refill(long nowMillis) {
    // A clock that goes back adds nothing and takes nothing. An idle time longer than one full
    // refill adds no more than a full refill, which also keeps the multiplication from overflowing.
    long fullRefillMillis = Math.ceilDiv(capacityCredits, creditsPerMilli);
    long elapsedMillis = Math.min(Math.max(0, nowMillis - refilledAtMillis), fullRefillMillis);
    credits = Math.min(capacityCredits, credits + elapsedMillis * creditsPerMilli);
    refilledAtMillis = Math.max(refilledAtMillis, nowMillis);
  }
}
