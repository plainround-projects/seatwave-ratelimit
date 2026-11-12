package io.plainround.seatwave.ratelimit.booking;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The booking service's settings, bound from {@code seatwave.booking.*} and checked at start-up.
 *
 * @param holdDuration how long a hold lasts; Module 1's hold is 300 seconds
 * @param readDelay a fault the slow-backend lab injects into the two list reads ({@code GET
 *     /v1/holds} and {@code GET /v1/bookings}); zero, the default, injects nothing
 * @param readWorkers how many delayed reads are served at once; the others wait in line, as
 *     requests wait for a database pool's connection. Module 12's shedding lab sets 8, so a laptop
 *     can send twice what booking serves; 10,000, the default, means no line
 */
@Validated
@ConfigurationProperties("seatwave.booking")
public record BookingProperties(
    @NotNull Duration holdDuration,
    @NotNull Duration readDelay,
    @Min(1) @Max(10_000) int readWorkers) {

  // Longer than any client timeout in this repository; a longer fault would test nothing new.
  private static final Duration MAX_DELAY = Duration.ofSeconds(30);

  /**
   * A hold must last some time; a missing value has its own problem line.
   *
   * @return true when the duration is positive or missing
   */
  @AssertTrue(message = "holdDuration must be positive")
  public boolean isHoldDurationPositive() {
    return holdDuration == null || holdDuration.isPositive();
  }

  /**
   * The injected delay must be from 0 to 30 s.
   *
   * @return true when the delay is inside that range or missing
   */
  @AssertTrue(message = "readDelay must be from 0 to 30s")
  public boolean isReadDelayInRange() {
    return readDelay == null || (!readDelay.isNegative() && readDelay.compareTo(MAX_DELAY) <= 0);
  }
}
