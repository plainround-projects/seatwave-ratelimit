package io.plainround.seatwave.ratelimit.booking;

/** Why a hold was not granted: the expected failures of {@code POST /v1/holds}. */
public sealed interface HoldRefusal {

  /** The seat has an active hold now, by another fan or by this fan under another key: 409. */
  record SeatTaken(String seatId) implements HoldRefusal {}

  /** The fan used this Idempotency-Key before for another seat: 422. */
  record KeyReused(String idempotencyKey) implements HoldRefusal {}
}
