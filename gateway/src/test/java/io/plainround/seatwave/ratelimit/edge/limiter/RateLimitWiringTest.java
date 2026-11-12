package io.plainround.seatwave.ratelimit.edge.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
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
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The limiter in the real gateway: it runs for every request, after the token check and the
 * identity filter (so a hold is keyed by the verified fan, never by a header the client sent), and
 * before any route (so a refused hold never reaches booking). Redis is {@link ScriptedRedis}; a
 * loopback stand-in plays the issuer's key set and booking, and counts what reaches it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitWiringTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final ECKey ISSUER_KEY = generate();
  private static final AtomicInteger ARRIVED = new AtomicInteger();
  private static final HttpServer BACKEND = backend();
  private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private static final long WAIT_MILLIS = 9_500;

  @LocalServerPort private int port;
  @Autowired private ScriptedRedis redis;

  @DynamicPropertySource
  static void pointAtTheStandIn(DynamicPropertyRegistry registry) {
    String base = "http://127.0.0.1:" + BACKEND.getAddress().getPort();
    registry.add("SEATWAVE_BOOKING_URI", () -> base);
    registry.add("SEATWAVE_CATALOG_URI", () -> base);
    registry.add("SEATWAVE_JWK_SET_URI", () -> base + "/.well-known/jwks.json");
    registry.add("management.server.port", () -> "0");
  }

  @AfterAll
  static void stopTheStandIn() {
    BACKEND.stop(0);
    CLIENT.close();
  }

  @BeforeEach
  void forgetEarlierRequests() {
    ARRIVED.set(0);
  }

  @Test
  void refusesAHoldOverTheLimitBeforeItReachesBooking() throws Exception {
    redis.refuseFor(WAIT_MILLIS);

    HttpResponse<String> response = hold("fan-42", "fan-7");

    assertThat(response.statusCode()).isEqualTo(429);
    assertThat(response.headers().firstValue("Retry-After")).hasValue("10");
    assertThat(ARRIVED).hasValue(0);
  }

  @Test
  void keysAHoldByTheVerifiedFanNotByTheSubjectHeaderTheClientSent() throws Exception {
    redis.allowWith(4);

    HttpResponse<String> response = hold("fan-42", "fan-7");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("RateLimit")).hasValue("\"holds\";r=4");
    assertThat(redis.calls().getLast().getFirst()).isEqualTo("seatwave:rl:holds:sub:fan-42");
    assertThat(ARRIVED).hasValue(1);
  }

  @Test
  void ignoresAnAddressTheClientWroteWhenItDoesNotComeFromATrustedProxy() throws Exception {
    redis.allowWith(39);
    HttpRequest read =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/events/1"))
            .timeout(TIMEOUT)
            .header("X-Forwarded-For", "203.0.113.9")
            .build();

    CLIENT.send(read, HttpResponse.BodyHandlers.ofString());

    // The test connects from 127.0.0.1, which is not in server.tomcat.remoteip.internal-proxies.
    assertThat(redis.calls().getLast().getFirst())
        .isEqualTo("seatwave:rl:seat-reads:addr:127.0.0.1");
  }

  private HttpResponse<String> hold(String tokenSubject, String forgedSubject)
      throws IOException, InterruptedException, JOSEException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/holds"))
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer " + token(tokenSubject))
            .header("X-SeatWave-Subject", forgedSubject)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"eventId\":1,\"seatId\":\"A-1\"}"))
            .build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String token(String subject) throws JOSEException {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer("http://issuer:8080")
            .audience("seatwave-api")
            .subject(subject)
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plus(Duration.ofMinutes(15))))
            .build();
    SignedJWT token =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(ISSUER_KEY.getKeyID()).build(), claims);
    token.sign(new ECDSASigner(ISSUER_KEY));
    return token.serialize();
  }

  private static ECKey generate() {
    try {
      return new ECKeyGenerator(Curve.P_256).keyID("issuer-key-1").generate();
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
            ARRIVED.incrementAndGet();
            answer(exchange, "{}".getBytes(StandardCharsets.UTF_8));
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

  /** Replaces Boot's template with the scripted one, on Boot's own connection factory. */
  @TestConfiguration(proxyBeanMethods = false)
  static class ScriptedStore {

    @Bean
    ScriptedRedis stringRedisTemplate(RedisConnectionFactory connections) {
      ScriptedRedis redis = new ScriptedRedis();
      redis.setConnectionFactory(connections);
      return redis;
    }
  }
}
