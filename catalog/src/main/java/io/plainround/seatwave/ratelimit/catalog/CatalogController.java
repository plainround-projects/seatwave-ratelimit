package io.plainround.seatwave.ratelimit.catalog;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Module 7's two catalog reads, {@code GET /v1/events/{eventId}} and {@code GET
 * /v1/events/{eventId}/seats}, with problem details (RFC 9457) for every refusal. The first is new
 * in this module and additive: the mobile app's seat screen asks for the event's name and sections.
 */
@RestController
public final class CatalogController {

  // Allow-lists: an event id is 1 to 18 digits, a section one capital letter, a cursor and a limit
  // 1 to 6 digits; anything else is refused before any work.
  private static final Pattern EVENT_ID = Pattern.compile("[1-9][0-9]{0,17}");
  private static final Pattern SECTION = Pattern.compile("[A-Z]");
  private static final Pattern NUMBER = Pattern.compile("[1-9][0-9]{0,5}");
  private static final String PROBLEMS = "https://seatwave.example/problems/";

  private final SeatCatalog catalog;
  private final CatalogProperties settings;

  /**
   * Creates the controller.
   *
   * @param catalog the seats
   * @param settings the page-size limits
   */
  public CatalogController(SeatCatalog catalog, CatalogProperties settings) {
    this.catalog = catalog;
    this.settings = settings;
  }

  /**
   * Describes one event.
   *
   * @param eventId the event's id, as the path holds it
   * @return 200 and the event; 400 for a malformed id; 404 when there is no such event
   */
  @GetMapping("/v1/events/{eventId}")
  public ResponseEntity<?> event(@PathVariable String eventId) {
    if (!EVENT_ID.matcher(eventId).matches()) {
      return problem(HttpStatus.BAD_REQUEST, "invalid-request", "eventId must be 1 to 18 digits");
    }
    Optional<Event> event = catalog.event(Long.parseLong(eventId));
    return event.<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(CatalogController::noEvent);
  }

  /**
   * Returns one page of a section's seats.
   *
   * @param eventId the event's id
   * @param section the section, one capital letter
   * @param cursor where the page starts, from the previous page; absent for the first page
   * @param limit the page size, 1 to the configured maximum; absent for the default
   * @return 200 and the page; 400 for a malformed or out-of-range value; 404 for no such event or
   *     section
   */
  @GetMapping("/v1/events/{eventId}/seats")
  public ResponseEntity<?> seats(
      @PathVariable String eventId,
      @RequestParam String section,
      // An empty text stands for "not given", so no parameter is ever null or Optional.
      @RequestParam(defaultValue = "") String cursor,
      @RequestParam(defaultValue = "") String limit) {
    if (!EVENT_ID.matcher(eventId).matches() || !SECTION.matcher(section).matches()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "invalid-request",
          "eventId must be 1 to 18 digits and section one capital letter");
    }
    int firstNumber = cursor.isEmpty() ? 1 : number(cursor);
    if (firstNumber < 1 || firstNumber > catalog.seatsPerSection()) {
      return problem(HttpStatus.BAD_REQUEST, "invalid-request", "cursor is not one this API gave");
    }
    int size = limit.isEmpty() ? settings.defaultPageSize() : number(limit);
    if (size < 1 || size > settings.maxPageSize()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "invalid-request",
          "limit must be from 1 to " + settings.maxPageSize());
    }
    SeatQuery query = new SeatQuery(Long.parseLong(eventId), section, firstNumber, size);
    return catalog
        .seats(query)
        .<ResponseEntity<?>>map(ResponseEntity::ok)
        .orElseGet(CatalogController::noEvent);
  }

  // A malformed number becomes 0, which both callers refuse with their own message.
  private static int number(String text) {
    return NUMBER.matcher(text).matches() ? Integer.parseInt(text) : 0;
  }

  private static ResponseEntity<?> noEvent() {
    return problem(HttpStatus.NOT_FOUND, "not-found", "there is no such event or section");
  }

  private static ResponseEntity<?> problem(HttpStatus status, String type, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(PROBLEMS + type));
    return ResponseEntity.of(problem).build();
  }
}
