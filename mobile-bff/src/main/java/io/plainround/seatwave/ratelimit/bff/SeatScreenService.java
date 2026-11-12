package io.plainround.seatwave.ratelimit.bff;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds the mobile seat screen from four parts fetched in parallel: the event and the section's
 * seats from the catalog (essential), the fan's holds and bookings from the booking service
 * (optional). The screen takes as long as the slowest part, not the sum of all of them, and every
 * part is bounded by its own client timeout. Thread-safe: it holds only thread-safe clients.
 */
@Service
public final class SeatScreenService {

  private static final Logger LOG = LoggerFactory.getLogger(SeatScreenService.class);

  private final CatalogClient catalog;
  private final BookingClient booking;

  /**
   * Creates the service.
   *
   * @param catalog the catalog's client
   * @param booking the booking service's client
   */
  public SeatScreenService(CatalogClient catalog, BookingClient booking) {
    this.catalog = catalog;
    this.booking = booking;
  }

  /**
   * Builds one seat screen.
   *
   * @param eventId the event
   * @param section the section to show
   * @param subject the fan, as the gateway verified them
   * @return the screen, marked partial when holds or bookings did not arrive in time
   * @throws CatalogUnavailableException when the event or the seats cannot be read: without the
   *     seat map there is no screen to draw
   * @throws InterruptedException when the request's thread is interrupted while it waits
   */
  public SeatScreen load(long eventId, String section, String subject) throws InterruptedException {
    // One virtual thread per part, closed with the request: close() waits for every part, and
    // each part ends within its client timeout, so nothing outlives the request.
    try (ExecutorService parts = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<CatalogClient.CatalogEvent> event = parts.submit(() -> catalog.event(eventId));
      Future<List<String>> seats = parts.submit(() -> catalog.seatIds(eventId, section));
      Future<List<String>> holds = parts.submit(() -> booking.heldSeatIds(eventId, subject));
      Future<List<String>> bookings = parts.submit(() -> booking.bookedSeatIds(eventId, subject));
      String eventName = essential(event).name();
      List<String> seatIds = essential(seats);
      List<String> missing = new ArrayList<>();
      List<String> held = optional(holds, "holds", missing);
      List<String> booked = optional(bookings, "bookings", missing);
      return new SeatScreen(eventName, section, seatIds, held, booked, !missing.isEmpty(), missing);
    }
  }

  private static <T> T essential(Future<T> part) throws InterruptedException {
    try {
      return part.get();
    } catch (ExecutionException failed) {
      throw new CatalogUnavailableException(failed.getCause());
    }
  }

  // An optional part that failed or ran out of time becomes an empty list and a name in
  // "missing": the screen is still drawn, and the app knows which part to ask for again.
  private static List<String> optional(Future<List<String>> part, String name, List<String> missing)
      throws InterruptedException {
    try {
      return part.get();
    } catch (ExecutionException failed) {
      LOG.warn("seat screen part {} missing: {}", name, failed.getCause().toString());
      missing.add(name);
      return List.of();
    }
  }
}
