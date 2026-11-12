package io.plainround.seatwave.ratelimit.catalog;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The catalog's settings, bound from {@code seatwave.catalog.*} and checked at start-up. Spring
 * Boot reports every violated rule together and refuses to start, so a bad value never reaches a
 * request.
 *
 * @param eventId the one event of the lab, Module 1's arena on-sale
 * @param eventName the event's name as fans see it
 * @param venue the venue's name
 * @param startsAt when the event starts, in UTC
 * @param sections how many sections the arena has, named A, B, C and on; Module 1 has 20 (A to T)
 * @param seatsPerSection seats in each section; Module 1 has 1,000
 * @param defaultPageSize seats per page when a client asks for no limit; Module 7 uses 100
 * @param maxPageSize the largest page a client may ask for; Module 7 allows 500
 */
@Validated
@ConfigurationProperties("seatwave.catalog")
public record CatalogProperties(
    @Min(1) long eventId,
    @NotNull @Pattern(regexp = "[A-Za-z0-9 ,.'-]{1,80}") String eventName,
    @NotNull @Pattern(regexp = "[A-Za-z0-9 ,.'-]{1,80}") String venue,
    @NotNull Instant startsAt,
    @Min(1) @Max(26) int sections,
    @Min(1) @Max(100_000) int seatsPerSection,
    @Min(1) int defaultPageSize,
    @Min(1) @Max(10_000) int maxPageSize) {

  /**
   * The cross-key rule, reported with the others: a default page larger than the largest allowed
   * page would refuse requests that name no limit. It holds when the maximum is itself invalid,
   * because that key already has its own problem line and a second line would only repeat it.
   *
   * @return true when the default page fits inside the maximum
   */
  @AssertTrue(message = "defaultPageSize must not be larger than maxPageSize")
  public boolean isDefaultPageInsideMaximum() {
    return maxPageSize < 1 || defaultPageSize <= maxPageSize;
  }
}
