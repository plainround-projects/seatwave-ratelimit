package io.plainround.seatwave.ratelimit.edge.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The fallback gives each instance its share of a limit, per key, and refills at its share. */
class LocalBucketsTest {

  private static final int GATEWAY_INSTANCES = 3;
  // 40 tokens at 20 per second, shared by 3 instances: 13 at once and 20 per 3 s here.
  private static final LimitPolicy SEAT_READS =
      new LimitPolicy(
          "seat-reads",
          "GET",
          "/v1/events",
          LimitPolicy.KeySource.ADDRESS,
          40,
          20,
          Duration.ofSeconds(1),
          LimitPolicy.StoreDown.LOCAL,
          false);
  private static final int LOCAL_SHARE = 13;
  // A third of 20 per second: one token every 150 ms.
  private static final Duration ONE_LOCAL_TOKEN = Duration.ofMillis(150);
  private static final int ATTEMPTS = 100;

  private final MovableClock clock = new MovableClock(Instant.parse("2026-11-12T09:00:00Z"));
  private final LocalBuckets local = new LocalBuckets(GATEWAY_INSTANCES, clock);

  @Test
  void allowsThisInstancesShareOfTheBurst() {
    assertThat(allowedOf(ATTEMPTS, "addr:203.0.113.7")).isEqualTo(LOCAL_SHARE);
  }

  @Test
  void refillsAtThisInstancesShareOfTheRate() {
    allowedOf(ATTEMPTS, "addr:203.0.113.7");

    clock.advance(ONE_LOCAL_TOKEN);

    assertThat(allowedOf(ATTEMPTS, "addr:203.0.113.7")).isEqualTo(1);
  }

  @Test
  void keepsOneBucketPerKey() {
    allowedOf(ATTEMPTS, "addr:203.0.113.7");

    assertThat(allowedOf(ATTEMPTS, "addr:198.51.100.9")).isEqualTo(LOCAL_SHARE);
  }

  @Test
  void givesEveryInstanceAtLeastOneTokenOfASmallLimit() {
    LimitPolicy tiny =
        new LimitPolicy(
            "tiny",
            "ANY",
            "/",
            LimitPolicy.KeySource.SUBJECT,
            1,
            1,
            Duration.ofSeconds(1),
            LimitPolicy.StoreDown.LOCAL,
            false);

    assertThat(local.take(tiny, "sub:fan-42").isAllowed()).isTrue();
  }

  private long allowedOf(int attempts, String limitKey) {
    return IntStream.range(0, attempts)
        .filter(attempt -> local.take(SEAT_READS, limitKey).isAllowed())
        .count();
  }
}
