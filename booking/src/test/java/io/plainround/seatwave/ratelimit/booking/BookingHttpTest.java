package io.plainround.seatwave.ratelimit.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The booking stand-in through real HTTP: it trusts only the gateway's identity header, refuses
 * what it cannot read before doing any work, and shows a fan only their own holds. One context
 * serves every test, so each test uses its own fan and its own seats.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BookingHttpTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private static final String SUBJECT = "X-SeatWave-Subject";

  @LocalServerPort private int port;

  @AfterAll
  static void closeClient() {
    CLIENT.close();
  }

  @Test
  void refusesAHoldWithoutTheGatewaysIdentity() throws Exception {
    HttpResponse<String> response =
        hold(Optional.empty(), "key-1", "{\"eventId\":1,\"seatId\":\"B-1\"}");

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).contains("problems/no-identity");
  }

  @Test
  void refusesASubjectThatTheIssuerNeverWrites() throws Exception {
    HttpResponse<String> response =
        hold(Optional.of("admin"), "key-1", "{\"eventId\":1,\"seatId\":\"B-2\"}");

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void createsAHoldAndNamesItInTheLocationHeader() throws Exception {
    HttpResponse<String> response =
        hold(Optional.of("fan-101"), "key-1", "{\"eventId\":1,\"seatId\":\"B-3\"}");

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.headers().firstValue("Location"))
        .hasValueSatisfying(location -> assertThat(location).startsWith("/v1/holds/"));
    assertThat(response.body()).contains("\"subject\":\"fan-101\"", "\"seatId\":\"B-3\"");
  }

  @Test
  void answersConflictForASeatSomeoneElseHolds() throws Exception {
    hold(Optional.of("fan-102"), "key-1", "{\"eventId\":1,\"seatId\":\"B-4\"}");

    HttpResponse<String> response =
        hold(Optional.of("fan-103"), "key-1", "{\"eventId\":1,\"seatId\":\"B-4\"}");

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(response.body()).contains("problems/seat-taken", "B-4 is already held");
  }

  @Test
  void answersUnprocessableForAKeyReusedForAnotherSeat() throws Exception {
    hold(Optional.of("fan-104"), "key-1", "{\"eventId\":1,\"seatId\":\"B-5\"}");

    HttpResponse<String> response =
        hold(Optional.of("fan-104"), "key-1", "{\"eventId\":1,\"seatId\":\"B-6\"}");

    assertThat(response.statusCode()).isEqualTo(422);
    assertThat(response.body()).contains("problems/key-reused");
  }

  @Test
  void refusesAMalformedIdempotencyKey() throws Exception {
    HttpResponse<String> response =
        hold(Optional.of("fan-105"), "key with spaces", "{\"eventId\":1,\"seatId\":\"B-7\"}");

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("problems/invalid-request");
  }

  @Test
  void refusesAMalformedSeat() throws Exception {
    HttpResponse<String> response =
        hold(Optional.of("fan-106"), "key-1", "{\"eventId\":1,\"seatId\":\"b-7\"}");

    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void refusesABodyWithAFieldTheContractDoesNotHave() throws Exception {
    HttpResponse<String> response =
        hold(Optional.of("fan-107"), "key-1", "{\"eventId\":1,\"seatId\":\"B-8\",\"price\":0}");

    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void refusesABodyWithoutASeatAsABadRequestNotAServerError() throws Exception {
    HttpResponse<String> response = hold(Optional.of("fan-108"), "key-1", "{\"eventId\":1}");

    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void refusesAnEventIdBelowOne() throws Exception {
    HttpResponse<String> response =
        hold(Optional.of("fan-109"), "key-1", "{\"eventId\":0,\"seatId\":\"B-9\"}");

    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void listsOnlyTheCallingFansHolds() throws Exception {
    hold(Optional.of("fan-110"), "key-1", "{\"eventId\":1,\"seatId\":\"B-10\"}");
    hold(Optional.of("fan-111"), "key-1", "{\"eventId\":1,\"seatId\":\"B-11\"}");

    HttpResponse<String> response = get("/v1/holds?eventId=1", Optional.of("fan-110"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"seatId\":\"B-10\"").doesNotContain("B-11");
  }

  @Test
  void answersAnEmptyListOfBookingsBecauseTheStandInHasNoConfirmStep() throws Exception {
    HttpResponse<String> response = get("/v1/bookings?eventId=1", Optional.of("fan-112"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"items\":[]}");
  }

  @Test
  void refusesAListWithoutTheGatewaysIdentity() throws Exception {
    assertThat(get("/v1/holds?eventId=1", Optional.empty()).statusCode()).isEqualTo(401);
    assertThat(get("/v1/bookings?eventId=1", Optional.empty()).statusCode()).isEqualTo(401);
  }

  @Test
  void refusesAListForAMalformedEventId() throws Exception {
    assertThat(get("/v1/holds?eventId=01", Optional.of("fan-113")).statusCode()).isEqualTo(400);
    assertThat(get("/v1/bookings?eventId=x", Optional.of("fan-113")).statusCode()).isEqualTo(400);
  }

  private HttpResponse<String> hold(Optional<String> subject, String key, String json)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/holds"))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            // Keys belong to one fan, and every test has its own fans, so tests never share a key.
            .header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(json));
    subject.ifPresent(name -> request.header(SUBJECT, name));
    return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path, Optional<String> subject)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(TIMEOUT);
    subject.ifPresent(name -> request.header(SUBJECT, name));
    return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
