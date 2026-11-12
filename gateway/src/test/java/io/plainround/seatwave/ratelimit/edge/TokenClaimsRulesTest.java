package io.plainround.seatwave.ratelimit.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** One test per claim rule; each token breaks exactly one rule and nothing else. */
class TokenClaimsRulesTest {

  private static final String ISSUER = "http://issuer:8080";
  private static final String AUDIENCE = "seatwave-api";
  private static final Instant NOW = Instant.parse("2026-11-11T09:00:00Z");
  private static final Duration SKEW = Duration.ofSeconds(30);
  private static final Duration LIFETIME = Duration.ofMinutes(15);

  private final TokenClaimsRules rules =
      new TokenClaimsRules(ISSUER, AUDIENCE, SKEW, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void acceptsATokenThatKeepsEveryRule() {
    assertThat(rules.refusal(valid().build())).isEmpty();
  }

  @Test
  void refusesATokenThatExpiredBeyondTheSkew() {
    Instant issued = NOW.minus(LIFETIME).minus(SKEW).minusSeconds(1);
    Jwt token = valid().issuedAt(issued).notBefore(issued).expiresAt(issued.plus(LIFETIME)).build();

    assertThat(rules.refusal(token)).contains(TokenRefusal.EXPIRED);
  }

  @Test
  void acceptsATokenThatExpiredInsideTheSkew() {
    Instant issued = NOW.minus(LIFETIME).minus(SKEW).plusSeconds(1);
    Jwt token = valid().issuedAt(issued).notBefore(issued).expiresAt(issued.plus(LIFETIME)).build();

    assertThat(rules.refusal(token)).isEmpty();
  }

  @Test
  void refusesATokenNotYetValidBeyondTheSkew() {
    Jwt token = valid().notBefore(NOW.plus(SKEW).plusSeconds(1)).build();

    assertThat(rules.refusal(token)).contains(TokenRefusal.NOT_YET_VALID);
  }

  @Test
  void refusesAnotherIssuer() {
    assertThat(rules.refusal(valid().issuer("http://evil.example").build()))
        .contains(TokenRefusal.WRONG_ISSUER);
  }

  @Test
  void refusesAnIssuerThatDiffersOnlyByATrailingSlash() {
    assertThat(rules.refusal(valid().issuer(ISSUER + "/").build()))
        .contains(TokenRefusal.WRONG_ISSUER);
  }

  @Test
  void refusesATokenForAnotherAudience() {
    assertThat(rules.refusal(valid().audience(List.of("partner-api")).build()))
        .contains(TokenRefusal.WRONG_AUDIENCE);
  }

  @Test
  void acceptsATokenThatNamesSeatWaveAmongSeveralAudiences() {
    assertThat(rules.refusal(valid().audience(List.of("partner-api", AUDIENCE)).build())).isEmpty();
  }

  @Test
  void refusesATokenWithoutAnExpiry() {
    Jwt token = valid().claims(claims -> claims.remove("exp")).build();

    assertThat(rules.refusal(token)).contains(TokenRefusal.MISSING_CLAIM);
  }

  @Test
  void refusesATokenWithoutASubject() {
    Jwt token = valid().claims(claims -> claims.remove("sub")).build();

    assertThat(rules.refusal(token)).contains(TokenRefusal.MISSING_CLAIM);
  }

  private static Jwt.Builder valid() {
    Instant issued = NOW.minusSeconds(60);
    return Jwt.withTokenValue("token")
        .header("alg", "ES256")
        .issuer(ISSUER)
        .audience(List.of(AUDIENCE))
        .subject("fan-42")
        .issuedAt(issued)
        .notBefore(issued)
        .expiresAt(issued.plus(LIFETIME));
  }
}
