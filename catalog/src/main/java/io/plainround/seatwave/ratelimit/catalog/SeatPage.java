package io.plainround.seatwave.ratelimit.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/**
 * One page of seats. {@code nextCursor} names where the next page starts; it is empty, and left out
 * of the JSON, on the last page.
 *
 * @param items the seats of this page, in seat order
 * @param nextCursor the cursor for the next page, or empty on the last page
 */
public record SeatPage(
    List<Seat> items, @JsonInclude(JsonInclude.Include.NON_EMPTY) String nextCursor) {

  /** Copies the items, so a page cannot change after it was built. */
  public SeatPage {
    items = List.copyOf(items);
    Objects.requireNonNull(nextCursor, "nextCursor");
  }
}
