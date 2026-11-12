package io.plainround.seatwave.ratelimit.catalog;

import java.util.Objects;

/**
 * A checked request for one page of seats.
 *
 * @param eventId the event
 * @param section the section's name
 * @param firstNumber the number of the page's first seat, from 1
 * @param limit how many seats the page may hold, from 1
 */
public record SeatQuery(long eventId, String section, int firstNumber, int limit) {

  /** Refuses a page that starts before seat 1 or holds no seat. */
  public SeatQuery {
    Objects.requireNonNull(section, "section");
    if (firstNumber < 1 || limit < 1) {
      throw new IllegalArgumentException("firstNumber and limit must be at least 1");
    }
  }
}
