package io.plainround.seatwave.ratelimit.labtools;

import java.time.Duration;
import java.util.Objects;

/**
 * One seat screen as the phone saw it.
 *
 * @param elapsed from the first request sent to the last answer read, on this machine
 * @param bytesToPhone the response bodies the phone received, in bytes; headers are not counted
 * @param isPartial true when holds or bookings were left out because they were late
 */
record ScreenResult(Duration elapsed, int bytesToPhone, boolean isPartial) {

  /** Refuses a negative time or size. */
  ScreenResult {
    Objects.requireNonNull(elapsed, "elapsed");
    if (elapsed.isNegative() || bytesToPhone < 0) {
      throw new IllegalArgumentException("elapsed and bytesToPhone must not be negative");
    }
  }
}
