package io.plainround.seatwave.ratelimit.bff;

import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads one fan's holds and bookings from the booking service, passing on the identity the gateway
 * verified. Thread-safe: the REST client is.
 */
@Component
public final class BookingClient {

  private static final String SUBJECT_HEADER = "X-SeatWave-Subject";

  private final RestClient booking;

  /**
   * Creates the client.
   *
   * @param booking the booking service's REST client, with its timeout
   */
  public BookingClient(@Qualifier("bookingRestClient") RestClient booking) {
    this.booking = booking;
  }

  /**
   * Returns the seats the fan holds now for one event.
   *
   * @param eventId the event
   * @param subject the fan, as the gateway named them
   * @return the held seat ids
   */
  public List<String> heldSeatIds(long eventId, String subject) {
    return seatIds("/v1/holds?eventId={eventId}", eventId, subject);
  }

  /**
   * Returns the seats the fan has booked for one event.
   *
   * @param eventId the event
   * @param subject the fan, as the gateway named them
   * @return the booked seat ids
   */
  public List<String> bookedSeatIds(long eventId, String subject) {
    return seatIds("/v1/bookings?eventId={eventId}", eventId, subject);
  }

  private List<String> seatIds(String path, long eventId, String subject) {
    SeatList list =
        booking
            .get()
            .uri(path, eventId)
            .header(SUBJECT_HEADER, subject)
            .retrieve()
            .body(SeatList.class);
    return list.items().stream().map(SeatItem::seatId).toList();
  }

  /**
   * A list of holds or bookings as the booking service sends it.
   *
   * @param items the entries
   */
  public record SeatList(List<SeatItem> items) {}

  /**
   * One hold or booking; only its seat is used.
   *
   * @param seatId the seat's id
   */
  public record SeatItem(String seatId) {}
}
