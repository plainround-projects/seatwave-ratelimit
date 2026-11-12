package io.plainround.seatwave.ratelimit.bff;

import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The mobile seat screen, {@code GET /v1/events/{eventId}/seat-screen?section=A}; the gateway
 * exposes it as {@code /mobile/v1/events/{eventId}/seat-screen}. It answers 200 with a whole or a
 * partial screen, 400 for a malformed request, 401 when no verified identity came with it, and 503
 * when the seat map itself is unavailable.
 */
@RestController
public final class SeatScreenController {

  private static final Pattern EVENT_ID = Pattern.compile("[1-9][0-9]{0,17}");
  private static final Pattern SECTION = Pattern.compile("[A-Z]");
  // The issuer's subjects look like fan-42; anything else did not come from the gateway.
  private static final Pattern SUBJECT = Pattern.compile("fan-[0-9]{1,9}");
  private static final String PROBLEMS = "https://seatwave.example/problems/";

  private final SeatScreenService screens;

  /**
   * Creates the controller.
   *
   * @param screens the service that builds the screen
   */
  public SeatScreenController(SeatScreenService screens) {
    this.screens = screens;
  }

  /**
   * Builds one seat screen.
   *
   * @param eventId the event, as the path holds it
   * @param section the section, one capital letter
   * @param subject the verified fan, set by the gateway; empty when absent
   * @return the screen or a problem
   * @throws InterruptedException when the request's thread is interrupted
   */
  @GetMapping("/v1/events/{eventId}/seat-screen")
  public ResponseEntity<?> seatScreen(
      @PathVariable String eventId,
      @RequestParam String section,
      @RequestHeader(name = "X-SeatWave-Subject", defaultValue = "") String subject)
      throws InterruptedException {
    if (!SUBJECT.matcher(subject).matches()) {
      // Only the gateway sets this header; a request without it did not come through the gateway.
      return problem(
          HttpStatus.UNAUTHORIZED, "no-identity", "no verified identity came with the request");
    }
    if (!EVENT_ID.matcher(eventId).matches() || !SECTION.matcher(section).matches()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "invalid-request",
          "eventId must be 1 to 18 digits and section one capital letter");
    }
    return ResponseEntity.ok(screens.load(Long.parseLong(eventId), section, subject));
  }

  /**
   * Turns a missing seat map into one 503, so the app retries instead of drawing an empty screen.
   *
   * @param unavailable the failure
   * @return 503 with {@code Retry-After: 1}
   */
  @ExceptionHandler(CatalogUnavailableException.class)
  public ResponseEntity<ProblemDetail> catalogUnavailable(CatalogUnavailableException unavailable) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE, "the seat map is not available now; try again");
    problem.setType(URI.create(PROBLEMS + "seat-map-unavailable"));
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header("Retry-After", "1")
        .body(problem);
  }

  private static ResponseEntity<?> problem(HttpStatus status, String type, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(PROBLEMS + type));
    return ResponseEntity.of(problem).build();
  }
}
