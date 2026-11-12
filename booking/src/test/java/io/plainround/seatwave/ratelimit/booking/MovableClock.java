package io.plainround.seatwave.ratelimit.booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A test clock that stands still until a test moves it forward. Thread-safe. */
final class MovableClock extends Clock {

  private final AtomicReference<Instant> now;

  MovableClock(Instant start) {
    this.now = new AtomicReference<>(start);
  }

  void advance(Duration step) {
    now.updateAndGet(current -> current.plus(step));
  }

  @Override
  public Instant instant() {
    return now.get();
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException("the test clock is always UTC");
  }
}
