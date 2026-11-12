package io.plainround.seatwave.ratelimit.bff;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads the catalog the way the mobile app used to: the event, then a section's seats page by page.
 * Thread-safe: the REST client is.
 */
@Component
public final class CatalogClient {

  // Module 7's largest page; a 1,000-seat section takes two pages.
  private static final int PAGE_SIZE = 500;
  // A bound on the cursor loop, so a catalog that kept sending cursors could not hold a request
  // forever: 4 pages of 500 is twice Module 1's 1,000-seat section.
  private static final int MAX_PAGES = 4;

  private final RestClient catalog;

  /**
   * Creates the client.
   *
   * @param catalog the catalog's REST client, with its timeout
   */
  public CatalogClient(@Qualifier("catalogRestClient") RestClient catalog) {
    this.catalog = catalog;
  }

  /**
   * Reads the event's name.
   *
   * @param eventId the event
   * @return the event as the catalog describes it
   * @throws org.springframework.web.client.RestClientException when the catalog fails or is slow
   */
  public CatalogEvent event(long eventId) {
    return catalog.get().uri("/v1/events/{eventId}", eventId).retrieve().body(CatalogEvent.class);
  }

  /**
   * Reads every seat id of one section, following the cursor. The pages depend on each other, so
   * they stay sequential; they run in parallel with the other parts of the screen.
   *
   * @param eventId the event
   * @param section the section
   * @return the section's seat ids, in order
   */
  public List<String> seatIds(long eventId, String section) {
    List<String> seatIds = new ArrayList<>();
    Optional<String> cursor = Optional.empty();
    for (int pageIndex = 0; pageIndex < MAX_PAGES; pageIndex++) {
      Optional<String> pageCursor = cursor;
      SeatPage page =
          catalog
              .get()
              .uri(builder ->
                  builder
                      .path("/v1/events/{eventId}/seats")
                      .queryParam("section", section)
                      .queryParam("limit", PAGE_SIZE)
                      .queryParamIfPresent("cursor", pageCursor)
                      .build(eventId))
              .retrieve()
              .body(SeatPage.class);
      page.items().forEach(seat -> seatIds.add(seat.seatId()));
      // The catalog leaves nextCursor out on the last page; Jackson then leaves it null.
      cursor = Optional.ofNullable(page.nextCursor());
      if (cursor.isEmpty()) {
        return List.copyOf(seatIds);
      }
    }
    throw new IllegalStateException(
        "the catalog sent more than " + MAX_PAGES + " pages for one section");
  }

  /**
   * The part of the catalog's event the screen needs; other fields are ignored.
   *
   * @param name the event's name
   */
  public record CatalogEvent(String name) {}

  /**
   * One page of seats as the catalog sends it.
   *
   * @param items the seats
   * @param nextCursor the next page's cursor, absent on the last page
   */
  public record SeatPage(List<SeatItem> items, String nextCursor) {}

  /**
   * One seat as the catalog sends it; only its id is used.
   *
   * @param seatId the seat's id
   */
  public record SeatItem(String seatId) {}
}
