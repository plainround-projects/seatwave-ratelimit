package io.plainround.seatwave.ratelimit.catalog;

import java.util.Objects;

/**
 * One seat of an event: its id (for example {@code A-7}), its section and its number in the
 * section. Whether it is held is the booking service's business, not the catalog's.
 *
 * @param seatId the seat's id, the section and the number joined by a hyphen
 * @param section the section's name
 * @param number the seat's number inside the section, from 1
 */
public record Seat(String seatId, String section, int number) {

  /** Checks that the id is the section and the number, so the two can never disagree. */
  public Seat {
    Objects.requireNonNull(section, "section");
    if (!(section + "-" + number).equals(seatId)) {
      throw new IllegalArgumentException("seatId must be section-number, but was " + seatId);
    }
  }
}
