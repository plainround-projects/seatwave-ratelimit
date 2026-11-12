package io.plainround.seatwave.ratelimit.bff;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The backend-for-frontend's settings, bound from {@code seatwave.bff.*} and checked at start-up.
 *
 * @param catalogUri where the catalog service listens, inside the private network
 * @param bookingUri where the booking service listens, inside the private network
 * @param connectTimeout how long one call may wait to connect
 * @param catalogTimeout how long one catalog call may take; the seat map is essential
 * @param bookingTimeout how long one booking call may take; holds and bookings are optional
 */
@Validated
@ConfigurationProperties("seatwave.bff")
public record MobileBffProperties(
    @NotNull URI catalogUri,
    @NotNull URI bookingUri,
    @NotNull Duration connectTimeout,
    @NotNull Duration catalogTimeout,
    @NotNull Duration bookingTimeout) {

  /**
   * Every timeout must be positive; a missing one has its own problem line.
   *
   * @return true when each present timeout is positive
   */
  @AssertTrue(message = "connectTimeout, catalogTimeout and bookingTimeout must be positive")
  public boolean isEveryTimeoutPositive() {
    return isPositiveOrMissing(connectTimeout)
        && isPositiveOrMissing(catalogTimeout)
        && isPositiveOrMissing(bookingTimeout);
  }

  private static boolean isPositiveOrMissing(Duration timeout) {
    return timeout == null || timeout.isPositive();
  }
}
