package io.plainround.seatwave.ratelimit.labtools;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stand-ins for the catalog and booking services on the loopback interface, with the endpoints and
 * JSON shapes of the module's services: the event, a section's seats in pages of 500, and one fan's
 * holds and bookings. Each answer waits a set service time first, so the client side sees the shape
 * of sequential against parallel calls. The booking delay can be changed between runs. Thread-safe:
 * the delay is held in an atomic reference.
 */
final class StubServices implements AutoCloseable {

  // Module 1's arena: one section holds 1,000 seats; Module 7's largest page is 500.
  private static final int SEATS_PER_SECTION = 1000;
  private static final int PAGE_SIZE = 500;
  private static final int BACKLOG = 128;

  private final HttpServer server;
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final Duration catalogDelay;
  private final AtomicReference<Duration> bookingDelay;

  StubServices(Duration catalogDelay, Duration bookingDelay) throws IOException {
    this.catalogDelay = catalogDelay;
    this.bookingDelay = new AtomicReference<>(bookingDelay);
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), BACKLOG);
    server.setExecutor(workers);
    server.createContext("/v1/events/1/seats", exchange -> seats(exchange));
    server.createContext(
        "/v1/events/1",
        exchange ->
            answer(
                exchange,
                catalogDelay,
                "{\"eventId\":1,\"name\":\"Arena on-sale night\",\"venue\":\"SeatWave Arena\",\"startsAt\":\"2026-12-04T19:00:00Z\"}"));
    server.createContext(
        "/v1/holds",
        exchange ->
            answer(
                exchange,
                this.bookingDelay.get(),
                "{\"items\":[{\"holdId\":\"6f1c2a52-8f8e-4c1e-9a43-0b6f5d6f2d11\",\"seatId\":\"A-7\",\"expiresAt\":\"2026-11-11T09:05:00Z\"}]}"));
    server.createContext(
        "/v1/bookings", exchange -> answer(exchange, this.bookingDelay.get(), "{\"items\":[]}"));
    server.start();
  }

  URI base() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  void slowBookingTo(Duration delay) {
    bookingDelay.set(delay);
  }

  private void seats(HttpExchange exchange) throws IOException {
    String query = exchange.getRequestURI().getQuery();
    int first =
        query != null && query.contains("cursor=")
            ? Integer.parseInt(query.replaceAll(".*cursor=([0-9]+).*", "$1"))
            : 1;
    int last = Math.min(SEATS_PER_SECTION, first + PAGE_SIZE - 1);
    StringBuilder body = new StringBuilder("{\"items\":[");
    for (int number = first; number <= last; number++) {
      body.append(number == first ? "" : ",")
          .append(
              String.format(
                  Locale.ROOT,
                  "{\"seatId\":\"A-%d\",\"section\":\"A\",\"number\":%d}",
                  number,
                  number));
    }
    body.append(']');
    if (last < SEATS_PER_SECTION) {
      body.append(",\"nextCursor\":\"").append(last + 1).append('"');
    }
    answer(exchange, catalogDelay, body.append('}').toString());
  }

  private static void answer(HttpExchange exchange, Duration delay, String json)
      throws IOException {
    try (exchange) {
      Thread.sleep(delay);
      byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    } catch (InterruptedException interrupted) {
      // The server is stopping: give up this answer and keep the thread's interrupt flag.
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() {
    server.stop(0);
    workers.close();
  }
}
