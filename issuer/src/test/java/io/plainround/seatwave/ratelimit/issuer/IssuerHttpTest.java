package io.plainround.seatwave.ratelimit.issuer;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The issuer stand-in through real HTTP: the key set holds only public keys, and a token it signs
 * verifies with the published key and carries the claims the gateway checks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IssuerHttpTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  // Section 7's brief: 15-minute tokens.
  private static final long LIFETIME_SECONDS = Duration.ofMinutes(15).toSeconds();

  @LocalServerPort private int port;

  @DynamicPropertySource
  static void keyFile(DynamicPropertyRegistry registry) throws Exception {
    // A throwaway key for this test run, in a temporary folder, never in the repository.
    Path file = Files.createTempDirectory("issuer-test").resolve("issuer-keys.json");
    KeyRingCli.main(new String[] {"generate", file.toString()});
    file.toFile().deleteOnExit();
    file.getParent().toFile().deleteOnExit();
    registry.add("seatwave.issuer.key-file", file::toString);
  }

  @AfterAll
  static void closeClient() {
    CLIENT.close();
  }

  @Test
  void publishesOnlyThePublicHalfOfItsKeyForFiveMinutes() throws Exception {
    HttpResponse<String> response = send("GET", "/.well-known/jwks.json");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).hasValue("max-age=300");
    JWKSet published = JWKSet.parse(response.body());
    assertThat(published.getKeys())
        .singleElement()
        .satisfies(key -> {
          assertThat(key.isPrivate()).isFalse();
          assertThat(response.body()).doesNotContain("\"d\"");
        });
  }

  @Test
  void signsATokenThatVerifiesWithThePublishedKey() throws Exception {
    ECKey published =
        (ECKey) JWKSet.parse(send("GET", "/.well-known/jwks.json").body()).getKeys().getFirst();

    SignedJWT token = SignedJWT.parse(accessToken(send("POST", "/v1/tokens?subject=fan-42")));

    assertThat(token.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
    assertThat(token.getHeader().getKeyID()).isEqualTo(published.getKeyID());
    assertThat(token.verify(new ECDSAVerifier(published))).isTrue();
  }

  @Test
  void writesTheClaimsTheGatewayChecks() throws Exception {
    SignedJWT token = SignedJWT.parse(accessToken(send("POST", "/v1/tokens?subject=fan-42")));

    var claims = token.getJWTClaimsSet();
    assertThat(claims.getIssuer()).isEqualTo("http://issuer:8080");
    assertThat(claims.getAudience()).containsExactly("seatwave-api");
    assertThat(claims.getSubject()).isEqualTo("fan-42");
    assertThat(claims.getJWTID()).isNotBlank();
    assertThat(claims.getNotBeforeTime()).isEqualTo(claims.getIssueTime());
    assertThat(claims.getExpirationTime().toInstant())
        .isEqualTo(claims.getIssueTime().toInstant().plusSeconds(LIFETIME_SECONDS));
  }

  @Test
  void refusesASubjectThatDoesNotLookLikeAFan() throws Exception {
    HttpResponse<String> response = send("POST", "/v1/tokens?subject=admin");

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("problems/invalid-request", "subject must look like");
  }

  @Test
  void refusesARequestWithoutASubject() throws Exception {
    assertThat(send("POST", "/v1/tokens").statusCode()).isEqualTo(400);
  }

  private static String accessToken(HttpResponse<String> response) {
    Map<?, ?> body = JSON.readValue(response.body(), Map.class);
    assertThat(body.get("tokenType")).isEqualTo("Bearer");
    assertThat(((Number) body.get("expiresIn")).longValue()).isEqualTo(LIFETIME_SECONDS);
    return (String) body.get("accessToken");
  }

  private HttpResponse<String> send(String method, String path)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(TIMEOUT)
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
