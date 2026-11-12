package io.plainround.seatwave.ratelimit.bff;

import java.util.List;
import java.util.Objects;

/**
 * Everything the mobile seat screen draws, in one answer. When an optional part could not be
 * fetched in time, {@code partial} is true and {@code missing} names it, so the app can draw the
 * seat map now and say "your holds are loading" instead of showing an error page.
 *
 * @param eventName the event's name
 * @param section the section shown
 * @param seats the section's seat ids, in order
 * @param heldByYou the seat ids this fan holds now
 * @param bookedByYou the seat ids this fan has booked
 * @param partial true when at least one optional part is missing
 * @param missing the names of the missing parts ({@code holds}, {@code bookings}); empty when whole
 */
public record SeatScreen(
    String eventName,
    String section,
    List<String> seats,
    List<String> heldByYou,
    List<String> bookedByYou,
    boolean partial,
    List<String> missing) {

  /** Copies every list and refuses a screen whose partial flag and missing list disagree. */
  public SeatScreen {
    Objects.requireNonNull(eventName, "eventName");
    Objects.requireNonNull(section, "section");
    seats = List.copyOf(seats);
    heldByYou = List.copyOf(heldByYou);
    bookedByYou = List.copyOf(bookedByYou);
    missing = List.copyOf(missing);
    if (partial == missing.isEmpty()) {
      throw new IllegalArgumentException("partial must be true exactly when a part is missing");
    }
  }
}
