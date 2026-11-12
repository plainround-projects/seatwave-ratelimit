package io.plainround.seatwave.ratelimit.booking;

import com.github.sviperll.result4j.Result;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The booking stand-in's three endpoints: {@code POST /v1/holds}, {@code GET /v1/holds?eventId=}
 * (the fan's own holds) and {@code GET /v1/bookings?eventId=} (the fan's own bookings, always empty
 * here: this stand-in has no confirm step). Every endpoint needs the identity the gateway put in
 * {@code X-SeatWave-Subject}, and authorizes next to the data: a fan sees only their own holds.
 */
@RestController
public final class BookingController {

  private static final Pattern SUBJECT = Pattern.compile("fan-[0-9]{1,9}");
  private static final Pattern EVENT_ID = Pattern.compile("[1-9][0-9]{0,17}");
  private static final Pattern SEAT_ID = Pattern.compile("[A-Z]-[1-9][0-9]{0,4}");
  private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final String PROBLEMS = "https://seatwave.example/problems/";

  private final HoldBook holds;
  private final Duration readDelay;
  // Fair, so the reads that wait are served in the order they came, as a pool's queue serves them.
  private final Semaphore readWorkers;

  /**
   * Creates the controller.
   *
   * @param holds the holds
   * @param settings the lab's injected read delay
   */
  public BookingController(HoldBook holds, BookingProperties settings) {
    this.holds = holds;
    this.readDelay = settings.readDelay();
    this.readWorkers = new Semaphore(settings.readWorkers(), true);
  }

  /**
   * Holds one seat for the fan.
   *
   * @param subject the verified fan, from the gateway
   * @param idempotencyKey the client's key for this action
   * @param command the event and the seat
   * @return 201 and the hold; 400, 401, 409 or 422 as a problem
   */
  @PostMapping("/v1/holds")
  public ResponseEntity<?> hold(
      @RequestHeader(name = "X-SeatWave-Subject", defaultValue = "") String subject,
      @RequestHeader(name = "Idempotency-Key", defaultValue = "") String idempotencyKey,
      @RequestBody HoldCommand command) {
    if (!SUBJECT.matcher(subject).matches()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "no-identity", "no verified identity came with the request");
    }
    if (!KEY.matcher(idempotencyKey).matches() || !SEAT_ID.matcher(command.seatId()).matches()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "invalid-request",
          "Idempotency-Key must be 1 to 64 of A-Z a-z 0-9 _ - and seatId like A-7");
    }
    return switch (holds.hold(subject, command.eventId(), command.seatId(), idempotencyKey)) {
      case Result.Success<Hold, HoldRefusal>(Hold hold) ->
          ResponseEntity.created(URI.create("/v1/holds/" + hold.holdId())).body(hold);
      case Result.Error<Hold, HoldRefusal>(HoldRefusal.SeatTaken taken) ->
          problem(HttpStatus.CONFLICT, "seat-taken", taken.seatId() + " is already held");
      case Result.Error<Hold, HoldRefusal>(HoldRefusal.KeyReused reused) ->
          problem(
              HttpStatus.UNPROCESSABLE_CONTENT,
              "key-reused",
              "this Idempotency-Key was used for another seat");
    };
  }

  /**
   * Lists the fan's active holds for one event.
   *
   * @param subject the verified fan
   * @param eventId the event
   * @return 200 and {@code {"items": [...]}}; 400 or 401 as a problem
   * @throws InterruptedException when the injected lab delay is interrupted
   */
  @GetMapping("/v1/holds")
  public ResponseEntity<?> myHolds(
      @RequestHeader(name = "X-SeatWave-Subject", defaultValue = "") String subject,
      @RequestParam(defaultValue = "") String eventId)
      throws InterruptedException {
    if (!SUBJECT.matcher(subject).matches()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "no-identity", "no verified identity came with the request");
    }
    if (!EVENT_ID.matcher(eventId).matches()) {
      return problem(HttpStatus.BAD_REQUEST, "invalid-request", "eventId must be 1 to 18 digits");
    }
    injectLabDelay();
    return ResponseEntity.ok(Map.of("items", holds.activeHolds(subject, Long.parseLong(eventId))));
  }

  /**
   * Lists the fan's bookings for one event: always empty in this stand-in, which has no confirm.
   *
   * @param subject the verified fan
   * @param eventId the event
   * @return 200 and {@code {"items": []}}; 400 or 401 as a problem
   * @throws InterruptedException when the injected lab delay is interrupted
   */
  @GetMapping("/v1/bookings")
  public ResponseEntity<?> myBookings(
      @RequestHeader(name = "X-SeatWave-Subject", defaultValue = "") String subject,
      @RequestParam(defaultValue = "") String eventId)
      throws InterruptedException {
    if (!SUBJECT.matcher(subject).matches()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "no-identity", "no verified identity came with the request");
    }
    if (!EVENT_ID.matcher(eventId).matches()) {
      return problem(HttpStatus.BAD_REQUEST, "invalid-request", "eventId must be 1 to 18 digits");
    }
    injectLabDelay();
    return ResponseEntity.ok(Map.of("items", List.of()));
  }

  // The slow-backend lab sets seatwave.booking.read-delay; production leaves it at zero. With
  // read-workers, at most that many delayed reads run at once and the rest wait for a turn: a
  // service with a fixed capacity, which a sleep alone on virtual threads would not be.
  private void injectLabDelay() throws InterruptedException {
    if (!readDelay.isPositive()) {
      return;
    }
    readWorkers.acquire();
    try {
      Thread.sleep(readDelay);
    } finally {
      readWorkers.release();
    }
  }

  private static ResponseEntity<?> problem(HttpStatus status, String type, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(PROBLEMS + type));
    return ResponseEntity.of(problem).build();
  }
}
