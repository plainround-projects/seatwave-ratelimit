package io.plainround.seatwave.ratelimit.booking;

import com.github.sviperll.result4j.Result;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * The lab's holds, in memory, with Module 1's rule that a seat has at most one active hold and
 * Module 7's rule that a repeated Idempotency-Key replays the first answer. Thread-safe: every
 * method runs under one lock, held only for map work, never across I/O.
 */
// ponytail: one lock and in-memory maps that only grow (every key is kept for the life of the
// process), enough for a stand-in; SeatWave keeps holds in the shared PostgreSQL database of
// Module 10, where a unique key and a conditional UPDATE keep these rules.
@Component
public final class HoldBook {

  private final ReentrantLock lock = new ReentrantLock();
  private final Map<String, Hold> activeBySeat = new HashMap<>();
  private final Map<String, Hold> byKey = new HashMap<>();
  private final Clock clock;
  private final Duration holdDuration;

  /**
   * Creates an empty book.
   *
   * @param clock the service's clock
   * @param settings the hold duration
   */
  public HoldBook(Clock clock, BookingProperties settings) {
    this.clock = clock;
    this.holdDuration = settings.holdDuration();
  }

  /**
   * Holds a seat for a fan, or replays the hold an earlier request with the same key made.
   *
   * @param subject the fan
   * @param eventId the event
   * @param seatId the seat
   * @param idempotencyKey the client's key for this one action
   * @return the hold, or why it was refused
   */
  public Result<Hold, HoldRefusal> hold(
      String subject, long eventId, String seatId, String idempotencyKey) {
    lock.lock();
    try {
      Instant now = clock.instant();
      // A key belongs to one fan: two fans may pick the same key without seeing each other's holds.
      Hold earlier = byKey.get(subject + " " + idempotencyKey);
      if (earlier != null) {
        return earlier.seatId().equals(seatId) && earlier.eventId() == eventId
            ? Result.success(earlier)
            : Result.error(new HoldRefusal.KeyReused(idempotencyKey));
      }
      String seatKey = eventId + " " + seatId;
      Hold owner = activeBySeat.get(seatKey);
      if (owner != null && owner.isActiveAt(now)) {
        return Result.error(new HoldRefusal.SeatTaken(seatId));
      }
      Hold granted = new Hold(UUID.randomUUID(), subject, eventId, seatId, now.plus(holdDuration));
      activeBySeat.put(seatKey, granted);
      byKey.put(subject + " " + idempotencyKey, granted);
      return Result.success(granted);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Lists the holds one fan has now for one event; another fan's holds are never visible.
   *
   * @param subject the fan
   * @param eventId the event
   * @return the active holds, in no particular order
   */
  public List<Hold> activeHolds(String subject, long eventId) {
    lock.lock();
    try {
      Instant now = clock.instant();
      return activeBySeat.values().stream()
          .filter(hold -> hold.subject().equals(subject) && hold.eventId() == eventId)
          .filter(hold -> hold.isActiveAt(now))
          .toList();
    } finally {
      lock.unlock();
    }
  }
}
