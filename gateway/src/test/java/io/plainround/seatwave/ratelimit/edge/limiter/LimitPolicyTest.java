package io.plainround.seatwave.ratelimit.edge.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Which requests a policy covers, and the window its RateLimit-Policy field names. */
class LimitPolicyTest {

  @Test
  void coversThePrefixAndWhatIsBelowItButNotALongerName() {
    LimitPolicy holds = policy("POST", "/v1/holds", 5, 1, Duration.ofSeconds(10));

    assertThat(holds.covers("POST", "/v1/holds")).isTrue();
    assertThat(holds.covers("POST", "/v1/holds/7")).isTrue();
    assertThat(holds.covers("POST", "/v1/holdsx")).isFalse();
  }

  @Test
  void coversOnlyItsOwnMethodUnlessItSaysAny() {
    LimitPolicy holds = policy("POST", "/v1/holds", 5, 1, Duration.ofSeconds(10));
    LimitPolicy everything = policy("ANY", "/", 30, 10, Duration.ofSeconds(1));

    assertThat(holds.covers("GET", "/v1/holds")).isFalse();
    assertThat(everything.covers("DELETE", "/v1/holds/7")).isTrue();
  }

  @Test
  void namesTheTimeAnEmptyBucketTakesToFillAsItsWindow() {
    // 5 tokens at 1 per 10 s fill in 50 s; 40 at 20 per second in 2 s; 1 at 1000 per second in
    // a millisecond, which the field rounds up to one whole second.
    assertThat(policy("POST", "/v1/holds", 5, 1, Duration.ofSeconds(10)).windowSeconds())
        .isEqualTo(50);
    assertThat(policy("GET", "/v1/events", 40, 20, Duration.ofSeconds(1)).windowSeconds())
        .isEqualTo(2);
    assertThat(policy("GET", "/v1/events", 1, 1_000, Duration.ofSeconds(1)).windowSeconds())
        .isEqualTo(1);
  }

  @Test
  void leavesAMissingRefillPeriodToItsOwnProblemLine() {
    assertThat(policy("ANY", "/", 1, 1, null).isRefillPeriodInRange()).isTrue();
  }

  private static LimitPolicy policy(
      String method, String pathPrefix, long capacity, long refillTokens, Duration refillPeriod) {
    return new LimitPolicy(
        "test",
        method,
        pathPrefix,
        LimitPolicy.KeySource.SUBJECT,
        capacity,
        refillTokens,
        refillPeriod,
        LimitPolicy.StoreDown.LOCAL,
        false);
  }
}
