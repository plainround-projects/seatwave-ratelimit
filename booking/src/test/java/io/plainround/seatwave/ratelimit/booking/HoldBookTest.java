package io.plainround.seatwave.ratelimit.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Module 1's one-active-hold-per-seat rule and Module 7's idempotency rule, in the stand-in. */
class HoldBookTest {

  private static final Instant START = Instant.parse("2026-12-04T10:00:00Z");
  // Module 1's hold.
  private static final Duration HOLD = Duration.ofSeconds(300);
  private static final long EVENT = 1;
  private static final int RACING_FANS = 32;

  private final MovableClock clock = new MovableClock(START);
  private final HoldBook book = new HoldBook(clock, new BookingProperties(HOLD, Duration.ZERO, 1));

  @Test
  void grantsAFreeSeatUntilTheHoldDurationHasPassed() {
    Result<Hold, HoldRefusal> granted = book.hold("fan-1", EVENT, "A-7", "key-1");

    assertThat(granted).isInstanceOf(Result.Success.class);
    assertThat(hold(granted).expiresAt()).isEqualTo(START.plus(HOLD));
  }

  @Test
  void refusesASeatThatAnotherFanHoldsNow() {
    book.hold("fan-1", EVENT, "A-7", "key-1");

    Result<Hold, HoldRefusal> second = book.hold("fan-2", EVENT, "A-7", "key-2");

    assertThat(second).isEqualTo(Result.error(new HoldRefusal.SeatTaken("A-7")));
  }

  @Test
  void grantsTheSeatAgainOnceTheFirstHoldHasExpired() {
    book.hold("fan-1", EVENT, "A-7", "key-1");
    clock.advance(HOLD);

    Result<Hold, HoldRefusal> second = book.hold("fan-2", EVENT, "A-7", "key-2");

    assertThat(hold(second).subject()).isEqualTo("fan-2");
  }

  @Test
  void keepsTheSameSeatNumberOfAnotherEventApart() {
    book.hold("fan-1", EVENT, "A-7", "key-1");

    Result<Hold, HoldRefusal> otherEvent = book.hold("fan-2", EVENT + 1, "A-7", "key-2");

    assertThat(otherEvent).isInstanceOf(Result.Success.class);
  }

  @Test
  void replaysTheFirstHoldWhenTheSameKeyComesAgain() {
    Hold first = hold(book.hold("fan-1", EVENT, "A-7", "key-1"));

    Hold replay = hold(book.hold("fan-1", EVENT, "A-7", "key-1"));

    assertThat(replay).isEqualTo(first);
  }

  @Test
  void refusesAKeyThatTheFanUsedForAnotherSeat() {
    book.hold("fan-1", EVENT, "A-7", "key-1");

    Result<Hold, HoldRefusal> reused = book.hold("fan-1", EVENT, "A-8", "key-1");

    assertThat(reused).isEqualTo(Result.error(new HoldRefusal.KeyReused("key-1")));
  }

  @Test
  void letsTwoFansPickTheSameKeyWithoutSeeingEachOther() {
    book.hold("fan-1", EVENT, "A-7", "key-1");

    Result<Hold, HoldRefusal> otherFan = book.hold("fan-2", EVENT, "A-8", "key-1");

    assertThat(hold(otherFan).seatId()).isEqualTo("A-8");
  }

  @Test
  void listsOnlyTheCallingFansActiveHolds() {
    book.hold("fan-1", EVENT, "A-7", "key-1");
    book.hold("fan-2", EVENT, "A-8", "key-2");
    clock.advance(HOLD.minusSeconds(1));
    book.hold("fan-1", EVENT, "A-9", "key-3");
    clock.advance(Duration.ofSeconds(1));

    List<Hold> holds = book.activeHolds("fan-1", EVENT);

    assertThat(holds).extracting(Hold::seatId).containsExactly("A-9");
  }

  @Test
  void grantsOneSeatToExactlyOneOfManyFansRacingForIt() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Result<Hold, HoldRefusal>>> attempts = new ArrayList<>();
    try (ExecutorService fans = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int fanIndex = 0; fanIndex < RACING_FANS; fanIndex++) {
        String subject = "fan-" + fanIndex;
        attempts.add(
            fans.submit(() -> {
              start.await();
              return book.hold(subject, EVENT, "A-7", "key-" + subject);
            }));
      }
      start.countDown();
    }
    long granted = 0;
    for (Future<Result<Hold, HoldRefusal>> attempt : attempts) {
      granted += attempt.get() instanceof Result.Success ? 1 : 0;
    }

    assertThat(granted).isEqualTo(1);
  }

  private static Hold hold(Result<Hold, HoldRefusal> result) {
    return switch (result) {
      case Result.Success<Hold, HoldRefusal>(Hold granted) -> granted;
      case Result.Error<Hold, HoldRefusal>(HoldRefusal refusal) ->
          throw new AssertionError("expected a hold, got " + refusal);
    };
  }
}
