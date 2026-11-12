package io.plainround.seatwave.ratelimit.edge;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The module's invariant, through the real gateway: no request reaches a booking route without a
 * token whose signature, issuer, audience and expiry are valid. A loopback stand-in plays both the
 * issuer's key set and the booking service, and records every request that reaches it; each bad
 * token must be answered 401 by the gateway and must never arrive there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EdgeInvariantTest {

  private static final String ISSUER = "http://issuer:8080";
  private static final String AUDIENCE = "seatwave-api";
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final Duration LIFETIME = Duration.ofMinutes(15);
  private static final ECKey ISSUER_KEY = generate("issuer-key-1");
  private static final ECKey OTHER_KEY = generate("issuer-key-1");
  private static final ConcurrentLinkedQueue<Map<String, List<String>>> ARRIVED =
      new ConcurrentLinkedQueue<>();
  private static final HttpServer BACKEND = backend();
  private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

  @LocalServerPort private int port;

  @DynamicPropertySource
  static void pointAtTheStandIn(DynamicPropertyRegistry registry) {
    String base = "http://127.0.0.1:" + BACKEND.getAddress().getPort();
    registry.add("SEATWAVE_BOOKING_URI", () -> base);
    registry.add("SEATWAVE_CATALOG_URI", () -> base);
    registry.add("SEATWAVE_MOBILE_BFF_URI", () -> base);
    registry.add("SEATWAVE_JWK_SET_URI", () -> base + "/.well-known/jwks.json");
    // The management port would be a second fixed port; tests share it with the random one.
    registry.add("management.server.port", () -> "0");
    // Module 12's limiter asks Redis on every request. Nothing listens on this port, so every
    // request here is decided by the limiter's local fallback, which allows these few; the
    // limiter's own rules are tested in the limiter package.
    int nowhere = closedPort();
    registry.add("spring.data.redis.host", () -> "127.0.0.1");
    registry.add("spring.data.redis.port", () -> Integer.toString(nowhere));
  }

  // Binds a free port and closes it again: nothing listens there afterwards.
  private static int closedPort() {
    try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return probe.getLocalPort();
    } catch (IOException unavailable) {
      throw new IllegalStateException("no free loopback port", unavailable);
    }
  }

  @AfterAll
  static void stopTheStandIn() {
    BACKEND.stop(0);
    CLIENT.close();
  }

  @BeforeEach
  void forgetEarlierRequests() {
    ARRIVED.clear();
  }

  static Stream<Arguments> badTokens() throws JOSEException {
    Instant now = Instant.now();
    return Stream.of(
        Arguments.of(
            "wrong signature", es256(OTHER_KEY, claims(ISSUER, AUDIENCE, now)), "signature"),
        Arguments.of(
            "wrong issuer",
            es256(ISSUER_KEY, claims("http://evil.example", AUDIENCE, now)),
            "wrong-issuer"),
        Arguments.of(
            "wrong audience",
            es256(ISSUER_KEY, claims(ISSUER, "partner-api", now)),
            "wrong-audience"),
        Arguments.of(
            "expired",
            es256(ISSUER_KEY, claims(ISSUER, AUDIENCE, now.minus(LIFETIME).minusSeconds(60))),
            "expired"),
        Arguments.of(
            "not yet valid",
            es256(ISSUER_KEY, claims(ISSUER, AUDIENCE, now.plusSeconds(120))),
            "not-yet-valid"),
        Arguments.of(
            "alg none", new PlainJWT(claims(ISSUER, AUDIENCE, now)).serialize(), "unsigned"),
        Arguments.of(
            "HS256 keyed with the public key",
            hmacWithPublicKey(claims(ISSUER, AUDIENCE, now)),
            "algorithm"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("badTokens")
  void refusesEveryBadTokenBeforeAnyBookingRoute(String name, String token, String reason)
      throws Exception {
    HttpResponse<String> response = get("/v1/holds?eventId=1", "Bearer " + token);

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).contains("\"reason\":\"" + reason + "\"");
    assertThat(response.headers().firstValue("WWW-Authenticate"))
        .hasValue("Bearer error=\"invalid_token\"");
    assertThat(ARRIVED).isEmpty();
  }

  @Test
  void refusesARequestWithoutAToken() throws Exception {
    HttpResponse<String> response = get("/v1/holds?eventId=1", "");

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
    assertThat(ARRIVED).isEmpty();
  }

  @Test
  void passesAValidTokenOnAsTheSubjectHeaderOnly() throws Exception {
    String token = es256(ISSUER_KEY, claims(ISSUER, AUDIENCE, Instant.now()));

    HttpResponse<String> response = get("/v1/holds?eventId=1", "Bearer " + token);

    assertThat(response.statusCode()).isEqualTo(200);
    Map<String, List<String>> forwarded = ARRIVED.peek();
    assertThat(forwarded).containsEntry("X-seatwave-subject", List.of("fan-42"));
    assertThat(forwarded).doesNotContainKey("Authorization");
  }

  @Test
  void replacesASubjectHeaderTheClientSentItself() throws Exception {
    String token = es256(ISSUER_KEY, claims(ISSUER, AUDIENCE, Instant.now()));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/holds?eventId=1"))
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .header("x-SeatWave-subject", "fan-7")
            .build();

    CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(ARRIVED.peek()).containsEntry("X-seatwave-subject", List.of("fan-42"));
  }

  @Test
  void letsAnyoneBrowseTheCatalogWithoutAToken() throws Exception {
    assertThat(get("/v1/events/1", "").statusCode()).isEqualTo(200);
  }

  @Test
  void refusesAPathNoRouteServesEvenWithAValidToken() throws Exception {
    String token = es256(ISSUER_KEY, claims(ISSUER, AUDIENCE, Instant.now()));

    assertThat(get("/v2/holds", "Bearer " + token).statusCode()).isEqualTo(403);
    assertThat(ARRIVED).isEmpty();
  }

  @Test
  void answersNotFoundForAPathWithoutAHandlerInsteadOfAskingForAToken() throws Exception {
    // Actuator lives on the management port; on the public port its path has no handler.
    HttpResponse<String> response = get("/actuator/health", "");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(ARRIVED).isEmpty();
  }

  private HttpResponse<String> get(String path, String authorization)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(TIMEOUT);
    if (!authorization.isEmpty()) {
      request.header("Authorization", authorization);
    }
    return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static JWTClaimsSet claims(String issuer, String audience, Instant issuedAt) {
    return new JWTClaimsSet.Builder()
        .issuer(issuer)
        .audience(audience)
        .subject("fan-42")
        .issueTime(Date.from(issuedAt))
        .notBeforeTime(Date.from(issuedAt))
        .expirationTime(Date.from(issuedAt.plus(LIFETIME)))
        .build();
  }

  private static String es256(ECKey key, JWTClaimsSet claims) throws JOSEException {
    SignedJWT token =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(), claims);
    token.sign(new ECDSASigner(key));
    return token.serialize();
  }

  private static String hmacWithPublicKey(JWTClaimsSet claims) throws JOSEException {
    SignedJWT token =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(ISSUER_KEY.getKeyID()).build(), claims);
    token.sign(new MACSigner(ISSUER_KEY.toECPublicKey().getEncoded()));
    return token.serialize();
  }

  private static ECKey generate(String keyId) {
    try {
      return new ECKeyGenerator(Curve.P_256).keyID(keyId).generate();
    } catch (JOSEException unavailable) {
      throw new IllegalStateException("the JDK cannot make a P-256 key", unavailable);
    }
  }

  private static HttpServer backend() {
    try {
      HttpServer server =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      byte[] keySet =
          new JWKSet(ISSUER_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      server.createContext("/.well-known/jwks.json", exchange -> answer(exchange, keySet));
      server.createContext(
          "/",
          exchange -> {
            // Everything that is not the key set is "a request that reached a service".
            ARRIVED.add(Map.copyOf(exchange.getRequestHeaders()));
            answer(exchange, "{\"items\":[]}".getBytes(StandardCharsets.UTF_8));
          });
      server.start();
      return server;
    } catch (IOException unavailable) {
      throw new IllegalStateException("the loopback stand-in cannot start", unavailable);
    }
  }

  private static void answer(HttpExchange exchange, byte[] body) throws IOException {
    try (exchange) {
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    }
  }
}
