package io.plainround.seatwave.ratelimit.booking.shedding;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Booking's shedding settings, bound from {@code seatwave.shedding.*} and checked at start-up.
 *
 * @param maxInFlight requests one instance serves at once; Section 7 of the module derives 64
 * @param retryAfter the wait a shed client is told, at least 1 s (Retry-After has whole seconds)
 */
@Validated
@ConfigurationProperties("seatwave.shedding")
public record SheddingProperties(
    @Min(1) @Max(100_000) int maxInFlight, @NotNull Duration retryAfter) {

  private static final Duration SHORTEST_RETRY_AFTER = Duration.ofSeconds(1);

  /**
   * Retry-After must be at least one second.
   *
   * @return true when it is or when it is missing
   */
  @AssertTrue(message = "retryAfter must be at least 1s")
  public boolean isRetryAfterLongEnough() {
    return retryAfter == null || retryAfter.compareTo(SHORTEST_RETRY_AFTER) >= 0;
  }
}
