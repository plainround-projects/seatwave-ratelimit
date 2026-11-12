package io.plainround.seatwave.ratelimit.booking;

import java.util.Objects;

/**
 * The body of {@code POST /v1/holds}.
 *
 * @param eventId the event
 * @param seatId the seat, for example {@code A-7}
 */
public record HoldCommand(long eventId, String seatId) {

  /** Refuses a body without a seat or with an event id below 1. */
  public HoldCommand {
    Objects.requireNonNull(seatId, "seatId");
    if (eventId < 1) {
      throw new IllegalArgumentException("eventId must be at least 1");
    }
  }
}
