package io.plainround.seatwave.ratelimit.limits;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.LongStream;

/**
 * Runs the four limiting algorithms on fixed timelines and prints what each one allows. Every time
 * is written into the program, never read from a clock, so the output is the same on every run and
 * on every machine.
 *
 * <ol>
 *   <li>SeatWave's hold bucket (5 tokens, 1 back every 10 s) over ten seconds: a burst of 8, then a
 *       request every 3 s.
 *   <li>The boundary burst: three bursts of 6 at 59 s, 60 s and 90 s against "6 per 60 s" in each
 *       algorithm.
 *   <li>One minute of a bot at 50 holds per second and two fans, through the hold bucket.
 * </ol>
 */
public final class AlgorithmDemo {

  private static final long HOLD_CAPACITY = 5;
  private static final Duration HOLD_REFILL_PERIOD = Duration.ofSeconds(10);
  private static final long LIMIT_PER_WINDOW = 6;
  private static final Duration WINDOW = Duration.ofSeconds(60);
  private static final long BURST_SIZE = 6;
  private static final long BURST_SPACING_MILLIS = 100;
  private static final long[] BURST_STARTS_MILLIS = {59_000, 60_000, 90_000};
  private static final long BOT_INTERVAL_MILLIS = 20;
  private static final long MINUTE_MILLIS = 60_000;
  private static final long MILLIS_PER_SECOND = 1_000;

  private AlgorithmDemo() {}

  /**
   * Prints the three parts.
   *
   * @param arguments none are read
   */
  public static void main(String[] arguments) {
    holdBucketOverTenSeconds();
    boundaryBurst();
    botAndFans();
  }

  private static void holdBucketOverTenSeconds() {
    System.out.println(
        String.format(
            Locale.ROOT,
            "1. The hold bucket: capacity %d, 1 token back every %d s, starting full",
            HOLD_CAPACITY,
            HOLD_REFILL_PERIOD.toSeconds()));
    System.out.println("   time s  tokens before  decision  remaining  Retry-After ms  header s");
    TokenBucket bucket = new TokenBucket(HOLD_CAPACITY, 1, HOLD_REFILL_PERIOD, 0);
    long[] requestTimesMillis = {0, 100, 200, 300, 400, 500, 600, 700, 3_000, 6_000, 9_000, 10_000};
    for (long requestMillis : requestTimesMillis) {
      double tokensBefore = bucket.tokens(requestMillis);
      Decision decision = bucket.tryAcquire(requestMillis);
      System.out.println(
          String.format(
              Locale.ROOT,
              "   %6.1f  %13.2f  %-8s  %9d  %14d  %8d",
              requestMillis / (double) MILLIS_PER_SECOND,
              tokensBefore,
              decision.isAllowed() ? "allowed" : "refused",
              decision.remaining(),
              decision.retryAfterMillis(),
              // Retry-After carries whole seconds (RFC 9110), so the wait is rounded up, never
              // down.
              Math.ceilDiv(decision.retryAfterMillis(), MILLIS_PER_SECOND)));
    }
  }

  private static void boundaryBurst() {
    System.out.println();
    System.out.println(
        "2. The boundary burst: 6 requests 100 ms apart starting at 59 s, 60 s and 90 s;"
            + " each limiter allows 6 per 60 s");
    System.out.println(
        "   algorithm               at 59 s  at 60 s  at 90 s  total  most in any 60 s  state kept"
            + " per key");
    List<Long> burstTimes = new ArrayList<>();
    for (long startMillis : BURST_STARTS_MILLIS) {
      LongStream.range(0, BURST_SIZE)
          .forEach(index -> burstTimes.add(startMillis + index * BURST_SPACING_MILLIS));
    }
    printBurstRow(
        "fixed window",
        () -> new FixedWindow(LIMIT_PER_WINDOW, WINDOW),
        burstTimes,
        "1 count + window start");
    printBurstRow(
        "sliding log",
        () -> new SlidingLog(LIMIT_PER_WINDOW, WINDOW),
        burstTimes,
        "1 time per allowed request (up to 6)");
    printBurstRow(
        "sliding window counter",
        () -> new SlidingWindowCounter(LIMIT_PER_WINDOW, WINDOW),
        burstTimes,
        "2 counts + window start");
    printBurstRow(
        "token bucket (6, 6/60 s)",
        () -> new TokenBucket(LIMIT_PER_WINDOW, LIMIT_PER_WINDOW, WINDOW, 0),
        burstTimes,
        "tokens + last refill time");
  }

  private static void printBurstRow(
      String name, Supplier<RateLimiter> limiterFactory, List<Long> requestTimes, String state) {
    RateLimiter limiter = limiterFactory.get();
    List<Long> allowedTimes = new ArrayList<>();
    for (long requestMillis : requestTimes) {
      if (limiter.tryAcquire(requestMillis).isAllowed()) {
        allowedTimes.add(requestMillis);
      }
    }
    long[] perBurst = new long[BURST_STARTS_MILLIS.length];
    for (long allowedMillis : allowedTimes) {
      for (int burstIndex = 0; burstIndex < BURST_STARTS_MILLIS.length; burstIndex++) {
        long startMillis = BURST_STARTS_MILLIS[burstIndex];
        if (allowedMillis >= startMillis
            && allowedMillis < startMillis + BURST_SIZE * BURST_SPACING_MILLIS) {
          perBurst[burstIndex]++;
        }
      }
    }
    System.out.println(
        String.format(
            Locale.ROOT,
            "   %-24s %7d  %7d  %7d  %5d  %16d  %s",
            name,
            perBurst[0],
            perBurst[1],
            perBurst[2],
            allowedTimes.size(),
            mostInAnyWindow(allowedTimes, WINDOW.toMillis()),
            state));
  }

  // The largest number of allowed requests inside any window of the given length: the busiest
  // window always starts at an allowed request, so trying each of them as a start is enough.
  private static long mostInAnyWindow(List<Long> allowedTimes, long windowMillis) {
    return allowedTimes.stream()
        .mapToLong(startMillis ->
            allowedTimes.stream()
                .filter(other -> other >= startMillis && other < startMillis + windowMillis)
                .count())
        .max()
        .orElse(0);
  }

  private static void botAndFans() {
    System.out.println();
    System.out.println(
        String.format(
            Locale.ROOT,
            "3. One minute through the hold bucket (%d, 1 per %d s), one bucket per caller",
            HOLD_CAPACITY,
            HOLD_REFILL_PERIOD.toSeconds()));
    System.out.println(
        "   caller                                  sent  allowed  refused  refused %  bound"
            + " holds");
    List<Long> botTimes =
        LongStream.iterate(
                0,
                timeMillis -> timeMillis < MINUTE_MILLIS,
                timeMillis -> timeMillis + BOT_INTERVAL_MILLIS)
            .boxed()
            .toList();
    printCallerRow("bot, 50 holds per second", botTimes);
    printCallerRow("fan, 4 holds at 5, 8, 12 and 20 s", List.of(5_000L, 8_000L, 12_000L, 20_000L));
    printCallerRow(
        "eager fan, 6 holds in 2.5 s", List.of(0L, 500L, 1_000L, 1_500L, 2_000L, 2_500L));
  }

  private static void printCallerRow(String caller, List<Long> requestTimes) {
    TokenBucket bucket = new TokenBucket(HOLD_CAPACITY, 1, HOLD_REFILL_PERIOD, 0);
    List<Long> allowedTimes =
        requestTimes.stream()
            .filter(timeMillis -> bucket.tryAcquire(timeMillis).isAllowed())
            .toList();
    long refused = requestTimes.size() - allowedTimes.size();
    System.out.println(
        String.format(
            Locale.ROOT,
            "   %-38s %5d  %7d  %7d  %9.1f  %s",
            caller,
            requestTimes.size(),
            allowedTimes.size(),
            refused,
            100.0 * refused / requestTimes.size(),
            boundHolds(allowedTimes)));
  }

  // The token bucket's promise, checked on every interval between two allowed requests: at most
  // capacity + rate x (length of the interval) requests are allowed inside it.
  private static boolean boundHolds(List<Long> allowedTimes) {
    for (long fromMillis : allowedTimes) {
      for (long toMillis : allowedTimes) {
        if (toMillis < fromMillis) {
          continue;
        }
        long inside =
            allowedTimes.stream().filter(time -> time >= fromMillis && time <= toMillis).count();
        // The bound in tokens, scaled by the refill period to stay in whole numbers.
        long boundScaled = HOLD_CAPACITY * HOLD_REFILL_PERIOD.toMillis() + (toMillis - fromMillis);
        if (inside * HOLD_REFILL_PERIOD.toMillis() > boundScaled) {
          return false;
        }
      }
    }
    return true;
  }
}
