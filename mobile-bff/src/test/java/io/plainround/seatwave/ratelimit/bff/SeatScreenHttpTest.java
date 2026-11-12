package io.plainround.seatwave.ratelimit.bff;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The seat screen through the BFF's real HTTP endpoint, against one loopback stand-in for both
 * services: the identity and input checks come before any inner call, and a missing seat map is one
 * 503 with Retry-After, never an empty screen.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeatScreenHttpTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private static final int OK = 200;
  private static final int UNAVAILABLE = 503;
  private static final AtomicInteger CATALOG_STATUS = new AtomicInteger(OK);
  private static final AtomicInteger INNER_CALLS = new AtomicInteger();
  private static HttpServer services;

  @LocalServerPort private int port;

  @DynamicPropertySource
  static void standIns(DynamicPropertyRegistry registry) throws IOException {
    services = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    services.createContext(
        "/v1/events/1/seats",
        exchange ->
            answer(
                exchange,
                CATALOG_STATUS.get(),
                "{\"items\":[{\"seatId\":\"A-1\"},{\"seatId\":\"A-2\"}]}"));
    services.createContext(
        "/v1/events/1",
        exchange -> answer(exchange, CATALOG_STATUS.get(), "{\"name\":\"Arena on-sale night\"}"));
    services.createContext(
        "/v1/holds", exchange -> answer(exchange, OK, "{\"items\":[{\"seatId\":\"A-2\"}]}"));
    services.createContext("/v1/bookings", exchange -> answer(exchange, OK, "{\"items\":[]}"));
    services.start();
    String base = "http://127.0.0.1:" + services.getAddress().getPort();
    registry.add("seatwave.bff.catalog-uri", () -> base);
    registry.add("seatwave.bff.booking-uri", () -> base);
    // Wider than the service's budgets, so a busy test machine cannot make a part late.
    registry.add("seatwave.bff.catalog-timeout", () -> "5s");
    registry.add("seatwave.bff.booking-timeout", () -> "5s");
  }

  @AfterEach
  void healTheCatalog() {
    CATALOG_STATUS.set(OK);
  }

  @AfterAll
  static void stopTheStandIns() {
    services.stop(0);
    CLIENT.close();
  }

  @Test
  void drawsTheWholeScreenInOneAnswer() throws Exception {
    HttpResponse<String> response =
        get("/v1/events/1/seat-screen?section=A", Optional.of("fan-42"));

    assertThat(response.statusCode()).isEqualTo(OK);
    assertThat(response.body())
        .isEqualTo(
            "{\"eventName\":\"Arena on-sale night\",\"section\":\"A\",\"seats\":[\"A-1\",\"A-2\"],"
                + "\"heldByYou\":[\"A-2\"],\"bookedByYou\":[],\"partial\":false,\"missing\":[]}");
  }

  @Test
  void refusesARequestThatDidNotComeThroughTheGatewayBeforeAnyInnerCall() throws Exception {
    int before = INNER_CALLS.get();

    HttpResponse<String> response = get("/v1/events/1/seat-screen?section=A", Optional.empty());

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).contains("problems/no-identity");
    assertThat(INNER_CALLS.get()).isEqualTo(before);
  }

  @Test
  void refusesALowerCaseSectionBeforeAnyInnerCall() throws Exception {
    int before = INNER_CALLS.get();

    HttpResponse<String> response =
        get("/v1/events/1/seat-screen?section=a", Optional.of("fan-42"));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(INNER_CALLS.get()).isEqualTo(before);
  }

  @Test
  void refusesAMalformedEventId() throws Exception {
    assertThat(get("/v1/events/01/seat-screen?section=A", Optional.of("fan-42")).statusCode())
        .isEqualTo(400);
  }

  @Test
  void answersServiceUnavailableWithRetryAfterWhenTheSeatMapIsMissing() throws Exception {
    CATALOG_STATUS.set(UNAVAILABLE);

    HttpResponse<String> response =
        get("/v1/events/1/seat-screen?section=A", Optional.of("fan-42"));

    assertThat(response.statusCode()).isEqualTo(UNAVAILABLE);
    assertThat(response.headers().firstValue("Retry-After")).hasValue("1");
    assertThat(response.body()).contains("problems/seat-map-unavailable");
  }

  private HttpResponse<String> get(String path, Optional<String> subject)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(TIMEOUT);
    subject.ifPresent(name -> request.header("X-SeatWave-Subject", name));
    return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static void answer(HttpExchange exchange, int status, String json) throws IOException {
    INNER_CALLS.incrementAndGet();
    try (exchange) {
      byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }
}
