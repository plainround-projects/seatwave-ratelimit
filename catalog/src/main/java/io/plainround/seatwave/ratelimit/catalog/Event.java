package io.plainround.seatwave.ratelimit.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One event as the catalog describes it.
 *
 * @param eventId the event's id
 * @param name the name fans see
 * @param venue the venue's name
 * @param startsAt when it starts, in UTC (written as ISO-8601)
 * @param sections the section names, in order
 */
public record Event(
    long eventId, String name, String venue, Instant startsAt, List<String> sections) {

  /** Checks every field, so an event without a name or sections cannot exist. */
  public Event {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(venue, "venue");
    Objects.requireNonNull(startsAt, "startsAt");
    sections = List.copyOf(sections);
  }
}
