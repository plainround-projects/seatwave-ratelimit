package io.plainround.seatwave.ratelimit.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The running Compose stack, from the Mac, through the ports compose.yaml publishes on 127.0.0.1:
 * the gateway on 21200, its management port on 21210 and the issuer on 21220. These tests need the
 * stack, so ./gradlew check never runs them; scripts/stack.sh test starts the stack, runs ./gradlew
 * stackTest and stops it again. Each test uses its own fan and seat, so the order of the tests and
 * a second run against the same stack change nothing.
 */
@Tag("stack")
class StackTest {

  private static final String GATEWAY = "http://127.0.0.1:21200";
  private static final String MANAGEMENT = "http://127.0.0.1:21210";
  private static final String ISSUER = "http://127.0.0.1:21220";
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  // How long a freshly started stack may answer the seat screen with 503 while its JVMs warm up.
  private static final Duration WARM_UP = Duration.ofSeconds(30);
  private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");
  // A fan number per run, so a second run against the same stack holds other seats.
  private static final int RUN = Math.floorMod(UUID.randomUUID().hashCode(), 100_000);

  private static HttpClient client;

  @BeforeAll
  static void openClient() {
    client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  @AfterAll
  static void closeClient() {
    client.close();
  }

  @Test
  void servesTheCatalogWithoutAToken() throws Exception {
    HttpResponse<String> response = send("GET", "/v1/events/1", "", "");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"eventId\":1");
  }

  @Test
  void refusesAHoldWithoutATokenBeforeItReachesBooking() throws Exception {
    HttpResponse<String> response = send("GET", "/v1/holds?eventId=1", "", "");

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
    assertThat(response.body()).contains("\"reason\":\"missing\"");
  }

  @Test
  void refusesSomethingThatIsNotATokenAsMalformed() throws Exception {
    HttpResponse<String> response = send("GET", "/v1/holds?eventId=1", "not-a-token", "");

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).contains("\"reason\":\"malformed\"");
  }

  @Test
  void refusesAWriteToTheCatalogAndAnUnknownVersionWithoutAToken() throws Exception {
    assertThat(send("PUT", "/v1/events/1", "", "").statusCode()).isEqualTo(401);
    assertThat(send("GET", "/v2/holds", "", "").statusCode()).isEqualTo(401);
  }

  @Test
  void refusesAnUnroutedPathEvenWithAGoodToken() throws Exception {
    HttpResponse<String> response = send("GET", "/v2/holds", tokenFor(fan(1)), "");

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.body()).contains("problems/not-routed");
  }

  @Test
  void holdsASeatForTheFanTheTokenNamesNotTheOneAForgedHeaderNames() throws Exception {
    String fan = fan(2);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(GATEWAY + "/v1/holds"))
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer " + tokenFor(fan))
            .header("x-seatwave-subject", "fan-1")
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", "stack-" + fan)
            .POST(HttpRequest.BodyPublishers.ofString(holdBody(fan)))
            .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.body()).contains("\"subject\":\"" + fan + "\"");
  }

  @Test
  void drawsTheSeatScreenWithTheFansOwnHoldThroughTheMobileRoute() throws Exception {
    String fan = fan(3);
    String token = tokenFor(fan);
    HttpResponse<String> held = send("POST", "/v1/holds", token, holdBody(fan));
    assertThat(held.statusCode()).isEqualTo(201);

    // Right after the stack starts, the catalog's first answers come from a cold JVM and can take
    // longer than the BFF's 150 ms budget for the seat map; the BFF then answers 503 with
    // Retry-After, as it should. So the screen is asked for again until the JVMs are warm.
    AtomicReference<HttpResponse<String>> answer = new AtomicReference<>();
    await()
        .atMost(WARM_UP)
        .pollInterval(Duration.ofMillis(200))
        .until(() -> {
          answer.set(send("GET", "/mobile/v1/events/1/seat-screen?section=T", token, ""));
          return answer.get().statusCode() != 503;
        });
    HttpResponse<String> screen = answer.get();

    assertThat(screen.statusCode()).isEqualTo(200);
    assertThat(screen.body())
        .contains("\"heldByYou\":[\"" + seat(fan) + "\"]", "\"partial\":false", "\"T-1000\"");
  }

  @Test
  void answersHealthOnTheManagementPortOnly() throws Exception {
    HttpRequest health =
        HttpRequest.newBuilder(URI.create(MANAGEMENT + "/actuator/health"))
            .timeout(TIMEOUT)
            .build();

    assertThat(client.send(health, HttpResponse.BodyHandlers.ofString()).body())
        .contains("\"status\":\"UP\"");
    assertThat(send("GET", "/actuator/health", "", "").statusCode()).isEqualTo(404);
  }

  private static String fan(int testNumber) {
    return "fan-" + (RUN * 10 + testNumber);
  }

  // Section T, one seat per fan: the fan number picks a seat from 1 to 1,000.
  private static String seat(String fan) {
    return "T-" + (Integer.parseInt(fan.substring("fan-".length())) % 1000 + 1);
  }

  private static String holdBody(String fan) {
    return "{\"eventId\":1,\"seatId\":\"" + seat(fan) + "\"}";
  }

  private static String tokenFor(String fan) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(ISSUER + "/v1/tokens?subject=" + fan))
            .timeout(TIMEOUT)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    String body = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    Matcher token = ACCESS_TOKEN.matcher(body);
    assertThat(token.find()).as("the issuer's answer %s holds a token", body).isTrue();
    return token.group(1);
  }

  private static HttpResponse<String> send(String method, String path, String token, String json)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(GATEWAY + path))
            .timeout(TIMEOUT)
            .method(
                method,
                json.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json));
    if (!token.isEmpty()) {
      request.header("Authorization", "Bearer " + token);
    }
    if (!json.isEmpty()) {
      request
          .header("Content-Type", "application/json")
          .header("Idempotency-Key", "stack-" + UUID.randomUUID());
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
