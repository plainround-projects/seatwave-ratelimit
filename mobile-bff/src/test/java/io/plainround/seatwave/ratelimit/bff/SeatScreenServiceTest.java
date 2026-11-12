package io.plainround.seatwave.ratelimit.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The backend-for-frontend's rules: the screen takes as long as its slowest essential part, a late
 * optional part makes it partial instead of failing it, and a missing seat map fails it.
 */
class SeatScreenServiceTest {

  // Wider than the service's own budgets (150 ms and 100 ms), so a test machine that is busy with
  // other work cannot turn a healthy call into a timeout; the rules do not depend on the numbers.
  private static final Duration CATALOG_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration BOOKING_TIMEOUT = Duration.ofSeconds(1);
  // Far longer than the booking timeout, so "late" cannot be confused with "a little slow".
  private static final Duration LATE = Duration.ofSeconds(10);

  private final AtomicReference<Duration> bookingDelay = new AtomicReference<>(Duration.ZERO);
  private final AtomicReference<Integer> catalogStatus = new AtomicReference<>(200);
  private HttpServer services;
  private HttpClient http;
  private SeatScreenService screens;

  @BeforeEach
  void startTheStandIns() throws IOException {
    services = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    services.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    services.createContext(
        "/v1/events/1/seats",
        exchange -> answer(exchange, Duration.ZERO, catalogStatus.get(), seats(exchange)));
    services.createContext(
        "/v1/events/1",
        exchange ->
            answer(
                exchange,
                Duration.ZERO,
                catalogStatus.get(),
                "{\"name\":\"Arena on-sale night\"}"));
    services.createContext(
        "/v1/holds",
        exchange ->
            answer(exchange, bookingDelay.get(), 200, "{\"items\":[{\"seatId\":\"A-7\"}]}"));
    services.createContext(
        "/v1/bookings", exchange -> answer(exchange, bookingDelay.get(), 200, "{\"items\":[]}"));
    services.start();
    URI base = URI.create("http://127.0.0.1:" + services.getAddress().getPort());
    MobileBffProperties settings =
        new MobileBffProperties(
            base, base, Duration.ofMillis(100), CATALOG_TIMEOUT, BOOKING_TIMEOUT);
    BackendClients clients = new BackendClients();
    http = clients.backendHttp(settings);
    screens =
        new SeatScreenService(
            new CatalogClient(clients.catalogRestClient(http, settings)),
            new BookingClient(clients.bookingRestClient(http, settings)));
  }

  @AfterEach
  void stopTheStandIns() {
    services.stop(0);
    http.close();
  }

  @Test
  void buildsAWholeScreenFromAllFiveCalls() throws Exception {
    SeatScreen screen = screens.load(1, "A", "fan-42");

    assertThat(screen.seats()).hasSize(1000).startsWith("A-1").endsWith("A-1000");
    assertThat(screen.heldByYou()).containsExactly("A-7");
    assertThat(screen.partial()).isFalse();
  }

  @Test
  void answersAPartialScreenWhenBookingIsLateInsteadOfWaitingForIt() throws Exception {
    bookingDelay.set(LATE);
    long start = System.nanoTime();

    SeatScreen screen = screens.load(1, "A", "fan-42");

    assertThat(screen.partial()).isTrue();
    assertThat(screen.missing()).containsExactlyInAnyOrder("holds", "bookings");
    assertThat(screen.seats()).hasSize(1000);
    // Bounded by the booking timeout, not by the 10 s the booking service would have taken.
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(LATE);
  }

  @Test
  void failsTheScreenWhenTheSeatMapIsUnavailable() {
    catalogStatus.set(503);

    assertThatThrownBy(() -> screens.load(1, "A", "fan-42"))
        .isInstanceOf(CatalogUnavailableException.class);
  }

  private static String seats(HttpExchange exchange) {
    String query = exchange.getRequestURI().getQuery();
    int first =
        query.contains("cursor=")
            ? Integer.parseInt(query.replaceAll(".*cursor=([0-9]+).*", "$1"))
            : 1;
    StringBuilder body = new StringBuilder("{\"items\":[");
    for (int number = first; number < first + 500; number++) {
      body.append(number == first ? "" : ",")
          .append("{\"seatId\":\"A-")
          .append(number)
          .append("\"}");
    }
    return body.append(first == 1 ? "],\"nextCursor\":\"501\"}" : "]}").toString();
  }

  private static void answer(HttpExchange exchange, Duration delay, int status, String json)
      throws IOException {
    try (exchange) {
      Thread.sleep(delay);
      byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    } catch (InterruptedException stopping) {
      Thread.currentThread().interrupt();
    }
  }
}
