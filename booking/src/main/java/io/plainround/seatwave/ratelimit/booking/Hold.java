package io.plainround.seatwave.ratelimit.booking;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One seat held by one fan until a moment.
 *
 * @param holdId the hold's id
 * @param subject the fan who holds it, as the gateway verified them
 * @param eventId the event
 * @param seatId the seat, for example {@code A-7}
 * @param expiresAt when the hold ends, in UTC
 */
public record Hold(UUID holdId, String subject, long eventId, String seatId, Instant expiresAt) {

  /** Refuses a hold without an id, an owner, a seat or an end. */
  public Hold {
    Objects.requireNonNull(holdId, "holdId");
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(seatId, "seatId");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }

  /**
   * Says whether the hold still holds its seat.
   *
   * @param now the current time
   * @return true before the hold's end
   */
  public boolean isActiveAt(Instant now) {
    return now.isBefore(expiresAt);
  }
}
