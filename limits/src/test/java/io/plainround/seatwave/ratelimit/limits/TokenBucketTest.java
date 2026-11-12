package io.plainround.seatwave.ratelimit.limits;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** The token bucket's three promises: the burst, the refill, and the exact Retry-After. */
class TokenBucketTest {

  private static final long CAPACITY = 5;
  private static final Duration TEN_SECONDS = Duration.ofSeconds(10);
  // Far more attempts than any bucket here holds, so only the bucket can stop them.
  private static final long ATTEMPTS = 100;
  private static final long TWO_SECONDS_MILLIS = 2_000;
  private static final long EIGHT_SECONDS_MILLIS = 8_000;

  @Test
  void allowsExactlyTheCapacityInABurstAndThenRefuses() {
    TokenBucket bucket = new TokenBucket(CAPACITY, 1, TEN_SECONDS, 0);

    long allowed =
        LongStream.range(0, ATTEMPTS).filter(index -> bucket.tryAcquire(0).isAllowed()).count();

    assertThat(allowed).isEqualTo(CAPACITY);
  }

  @Test
  void neverAllowsMoreThanCapacityPlusRateTimesElapsed() {
    TokenBucket bucket = new TokenBucket(CAPACITY, 1, TEN_SECONDS, 0);
    long oneHourMillis = Duration.ofHours(1).toMillis();
    long everyTwentyMillis = 20;

    long allowed =
        LongStream.iterate(0, time -> time <= oneHourMillis, time -> time + everyTwentyMillis)
            .filter(time -> bucket.tryAcquire(time).isAllowed())
            .count();

    // 5 + 3,600 s x 0.1 per s = 365, and a caller sending all the time gets exactly that.
    assertThat(allowed).isEqualTo(CAPACITY + oneHourMillis / TEN_SECONDS.toMillis());
  }

  @Test
  void tellsARefusedCallerTheExactWaitForTheNextToken() {
    TokenBucket bucket = new TokenBucket(CAPACITY, 1, TEN_SECONDS, 0);
    LongStream.range(0, CAPACITY).forEach(index -> bucket.tryAcquire(0));

    Decision refusal = bucket.tryAcquire(TWO_SECONDS_MILLIS);

    assertThat(refusal.isAllowed()).isFalse();
    assertThat(refusal.retryAfterMillis()).isEqualTo(EIGHT_SECONDS_MILLIS);
    long nextTokenMillis = TWO_SECONDS_MILLIS + refusal.retryAfterMillis();
    assertThat(bucket.tryAcquire(nextTokenMillis).isAllowed()).isTrue();
  }

  @Test
  void neverRefillsBeyondCapacityAfterALongIdleTime() {
    TokenBucket bucket = new TokenBucket(CAPACITY, 1, TEN_SECONDS, 0);
    long oneYearMillis = Duration.ofDays(365).toMillis();

    long allowed =
        LongStream.range(0, ATTEMPTS)
            .filter(index -> bucket.tryAcquire(oneYearMillis).isAllowed())
            .count();

    assertThat(allowed).isEqualTo(CAPACITY);
  }
}
