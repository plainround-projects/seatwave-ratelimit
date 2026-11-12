package io.plainround.seatwave.ratelimit.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

/**
 * The lab's catalog: one event with Module 1's arena, its sections named A, B, C and on, and its
 * seats numbered from 1 in each section. Nothing changes after start-up, so it is thread-safe.
 */
@Component
public final class SeatCatalog {

  private final CatalogProperties settings;
  private final Event event;

  /**
   * Builds the event from the settings.
   *
   * @param settings the checked catalog settings
   */
  public SeatCatalog(CatalogProperties settings) {
    this.settings = settings;
    List<String> sectionNames =
        IntStream.range(0, settings.sections())
            .mapToObj(index -> String.valueOf((char) ('A' + index)))
            .toList();
    this.event =
        new Event(
            settings.eventId(),
            settings.eventName(),
            settings.venue(),
            settings.startsAt(),
            sectionNames);
  }

  /**
   * Finds an event.
   *
   * @param eventId the event's id
   * @return the event, or empty when the catalog has no such event
   */
  public Optional<Event> event(long eventId) {
    return eventId == event.eventId() ? Optional.of(event) : Optional.empty();
  }

  /**
   * Returns one page of a section's seats.
   *
   * @param query the event, section, first seat number and page size, already checked
   * @return the page, or empty when the event or the section does not exist
   */
  public Optional<SeatPage> seats(SeatQuery query) {
    Optional<Event> found = event(query.eventId());
    if (found.isEmpty() || !found.get().sections().contains(query.section())) {
      return Optional.empty();
    }
    int lastNumber = Math.min(settings.seatsPerSection(), query.firstNumber() + query.limit() - 1);
    List<Seat> items = new ArrayList<>();
    for (int number = query.firstNumber(); number <= lastNumber; number++) {
      items.add(new Seat(query.section() + "-" + number, query.section(), number));
    }
    // The cursor is the number of the next page's first seat; past the last seat there is none.
    String nextCursor =
        lastNumber < settings.seatsPerSection() ? String.valueOf(lastNumber + 1) : "";
    return Optional.of(new SeatPage(items, nextCursor));
  }

  /**
   * Returns the number of seats in each section, for checking a cursor.
   *
   * @return seats per section
   */
  public int seatsPerSection() {
    return settings.seatsPerSection();
  }
}
