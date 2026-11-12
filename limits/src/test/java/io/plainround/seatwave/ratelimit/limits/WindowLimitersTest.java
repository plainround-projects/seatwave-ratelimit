package io.plainround.seatwave.ratelimit.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * The three window algorithms on Section 5c's timeline: six requests 100 ms apart at 59 s, at 60 s
 * and at 90 s, against a limit of 6 per 60 s. The fixed window lets the boundary burst through, the
 * sliding log never does, the sliding window counter lets some of it through; each tells a refused
 * caller a wait after which a request does pass.
 */
class WindowLimitersTest {

  private static final long LIMIT = 6;
  private static final Duration WINDOW = Duration.ofSeconds(60);
  private static final long[] STARTS_MILLIS = {59_000, 60_000, 90_000};
  private static final long GAP_MILLIS = 100;

  @Test
  void fixedWindowAllowsTwiceTheLimitAcrossTheBoundary() {
    assertThat(allowedOnTheTimeline(new FixedWindow(LIMIT, WINDOW))).isEqualTo(12);
  }

  @Test
  void slidingLogNeverAllowsMoreThanTheLimitInAnyWindow() {
    SlidingLog log = new SlidingLog(LIMIT, WINDOW);

    assertThat(allowedOnTheTimeline(log)).isEqualTo(LIMIT);
    assertThat(log.storedTimes()).isEqualTo((int) LIMIT);
  }

  @Test
  void slidingWindowCounterLetsPartOfTheBoundaryBurstThrough() {
    assertThat(allowedOnTheTimeline(new SlidingWindowCounter(LIMIT, WINDOW))).isEqualTo(9);
  }

  @Test
  void fixedWindowTellsARefusedCallerToWaitForTheNextWindow() {
    FixedWindow window = new FixedWindow(LIMIT, WINDOW);
    LongStream.range(0, LIMIT).forEach(index -> window.tryAcquire(0));

    Decision refusal = window.tryAcquire(WINDOW.toMillis() - 1);

    assertThat(refusal.retryAfterMillis()).isEqualTo(1);
    assertThat(window.tryAcquire(WINDOW.toMillis()).isAllowed()).isTrue();
  }

  @Test
  void slidingLogsWaitEndsWhenTheOldestRequestLeavesTheWindow() {
    SlidingLog log = new SlidingLog(LIMIT, WINDOW);
    LongStream.range(0, LIMIT).forEach(index -> log.tryAcquire(index * GAP_MILLIS));
    long now = 10_000;

    Decision refusal = log.tryAcquire(now);

    assertThat(refusal.retryAfterMillis()).isEqualTo(WINDOW.toMillis() - now);
    assertThat(log.tryAcquire(now + refusal.retryAfterMillis() - 1).isAllowed()).isFalse();
    assertThat(log.tryAcquire(now + refusal.retryAfterMillis()).isAllowed()).isTrue();
  }

  @Test
  void slidingCountersWaitIsLongEnoughAndNoLonger() {
    assertWaitIsExact(() -> new SlidingWindowCounter(LIMIT, WINDOW), 59_500);
    assertWaitIsExact(() -> new SlidingWindowCounter(LIMIT, WINDOW), 90_500);
    assertWaitIsExact(() -> new SlidingWindowCounter(LIMIT, WINDOW), 1_000);
  }

  @Test
  void slidingCounterForgetsEverythingAfterTwoQuietWindows() {
    SlidingWindowCounter counter = new SlidingWindowCounter(LIMIT, WINDOW);
    LongStream.range(0, LIMIT).forEach(index -> counter.tryAcquire(0));

    long twoWindowsLater = 2 * WINDOW.toMillis();

    assertThat(
            LongStream.range(0, LIMIT)
                .filter(index -> counter.tryAcquire(twoWindowsLater).isAllowed())
                .count())
        .isEqualTo(LIMIT);
  }

  @Test
  void refusesALimitOrAWindowThatCannotWork() {
    assertThatThrownBy(() -> new FixedWindow(0, WINDOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SlidingLog(LIMIT, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SlidingWindowCounter(LIMIT, Duration.ofNanos(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucket(0, 1, WINDOW, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // Runs the timeline up to the given time, then checks that the first refusal's wait is exact:
  // one millisecond earlier is still refused, the wait itself is allowed.
  private static void assertWaitIsExact(Supplier<SlidingWindowCounter> fresh, long untilMillis) {
    SlidingWindowCounter early = fresh.get();
    SlidingWindowCounter onTime = fresh.get();
    long refusedAt = fillUntilRefused(early, untilMillis);
    fillUntilRefused(onTime, untilMillis);
    long waitMillis = early.tryAcquire(refusedAt).retryAfterMillis();

    assertThat(early.tryAcquire(refusedAt + waitMillis - 1).isAllowed()).isFalse();
    assertThat(onTime.tryAcquire(refusedAt + waitMillis).isAllowed()).isTrue();
  }

  // Sends the timeline's requests up to a time, then one every 100 ms until one is refused.
  private static long fillUntilRefused(SlidingWindowCounter counter, long untilMillis) {
    for (long start : STARTS_MILLIS) {
      for (long index = 0; index < LIMIT && start + index * GAP_MILLIS <= untilMillis; index++) {
        counter.tryAcquire(start + index * GAP_MILLIS);
      }
    }
    long time = untilMillis;
    while (counter.tryAcquire(time).isAllowed()) {
      time += GAP_MILLIS;
    }
    return time;
  }

  private static long allowedOnTheTimeline(RateLimiter limiter) {
    long allowed = 0;
    for (long start : STARTS_MILLIS) {
      for (long index = 0; index < LIMIT; index++) {
        allowed += limiter.tryAcquire(start + index * GAP_MILLIS).isAllowed() ? 1 : 0;
      }
    }
    return allowed;
  }
}
