package io.plainround.seatwave.ratelimit.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** A decision that cannot mean anything cannot be built; the in-flight limit never waits. */
class DecisionAndInFlightTest {

  @Test
  void refusesAnAllowedDecisionThatAlsoSaysWait() {
    assertThatThrownBy(() -> new Decision(true, 1, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no Retry-After");
  }

  @Test
  void refusesARefusalThatDoesNotSayHowLongToWait() {
    assertThatThrownBy(() -> Decision.refused(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("above 0 ms");
  }

  @Test
  void refusesANegativeRemainingCount() {
    assertThatThrownBy(() -> Decision.allowed(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("remaining must be 0 or more");
  }

  @Test
  void admitsUpToTheLimitAndRefusesTheNextWithoutWaiting() {
    InFlightLimit limit = new InFlightLimit(2);

    assertThat(limit.tryEnter()).isTrue();
    assertThat(limit.tryEnter()).isTrue();
    assertThat(limit.tryEnter()).isFalse();
    assertThat(limit.inFlight()).isEqualTo(2);
  }

  @Test
  void admitsAgainOnceOneHasLeft() {
    InFlightLimit limit = new InFlightLimit(1);
    limit.tryEnter();

    limit.leave();

    assertThat(limit.tryEnter()).isTrue();
  }

  @Test
  void refusesALimitBelowOne() {
    assertThatThrownBy(() -> new InFlightLimit(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxInFlight must be 1 or more");
  }
}
