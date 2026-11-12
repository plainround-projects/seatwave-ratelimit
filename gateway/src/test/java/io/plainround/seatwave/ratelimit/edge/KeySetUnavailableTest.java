package io.plainround.seatwave.ratelimit.edge;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Fail safe when the issuer's key set cannot be fetched: a well-formed token is refused with 503
 * and Retry-After, never let through, and the reason tells the client to retry rather than to sign
 * in again.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KeySetUnavailableTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

  @LocalServerPort private int port;

  @DynamicPropertySource
  static void pointAtAPortWhereNothingListens(DynamicPropertyRegistry registry) throws IOException {
    int closedPort;
    // Bind a free port and close it again: nothing listens there afterwards, so every key-set
    // fetch is refused at once instead of waiting for a timeout.
    try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closedPort = probe.getLocalPort();
    }
    String nowhere = "http://127.0.0.1:" + closedPort;
    registry.add("SEATWAVE_JWK_SET_URI", () -> nowhere + "/.well-known/jwks.json");
    registry.add("SEATWAVE_BOOKING_URI", () -> nowhere);
    registry.add("management.server.port", () -> "0");
    // Module 12's limiter asks Redis on every request; here nothing listens there either, so the
    // limiter decides with its local fallback and the token check stays what is tested.
    registry.add("spring.data.redis.host", () -> "127.0.0.1");
    registry.add("spring.data.redis.port", () -> Integer.toString(closedPort));
  }

  @AfterAll
  static void closeClient() {
    CLIENT.close();
  }

  @Test
  void refusesAWellFormedTokenWithServiceUnavailableWhenTheKeySetCannotBeFetched()
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/holds?eventId=1"))
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer " + wellFormedToken())
            .build();

    HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.headers().firstValue("Retry-After")).hasValue("1");
    assertThat(response.body()).contains("\"reason\":\"key-set-unavailable\"");
  }

  private static String wellFormedToken() throws Exception {
    ECKey key = new ECKeyGenerator(Curve.P_256).keyID("issuer-key-1").generate();
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer("http://issuer:8080")
            .audience("seatwave-api")
            .subject("fan-42")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(Duration.ofMinutes(15))))
            .build();
    SignedJWT token =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(), claims);
    token.sign(new ECDSASigner(key));
    return token.serialize();
  }
}
