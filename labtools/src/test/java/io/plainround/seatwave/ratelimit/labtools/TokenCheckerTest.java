package io.plainround.seatwave.ratelimit.labtools;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Each of the six steps refuses what it guards, and only that: every bad token differs from the
 * good one in exactly one place, so a test can fail only for its own rule.
 */
class TokenCheckerTest {

  private static final String ISSUER = "http://issuer:8080";
  private static final String AUDIENCE = "seatwave-api";
  private static final String KEY_ID = "issuer-key-1";
  private static final String HEADER =
      "{\"alg\":\"ES256\",\"typ\":\"JWT\",\"kid\":\"" + KEY_ID + "\"}";
  private static final Instant NOW = Instant.parse("2026-11-11T09:00:00Z");
  private static final Duration LIFETIME = Duration.ofMinutes(15);
  private static final Duration SKEW = Duration.ofSeconds(30);
  // Just inside and just outside the 30 s skew.
  private static final Duration INSIDE_SKEW = Duration.ofSeconds(29);
  private static final Duration OUTSIDE_SKEW = Duration.ofSeconds(31);

  private static KeyPair issuerKeys;
  private static KeyPair otherKeys;
  private static TokenChecker checker;

  @BeforeAll
  static void makeKeys() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    issuerKeys = generator.generateKeyPair();
    otherKeys = generator.generateKeyPair();
    checker =
        new TokenChecker(
            Map.of(KEY_ID, issuerKeys.getPublic()),
            ISSUER,
            AUDIENCE,
            SKEW,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void acceptsAGoodTokenAndNamesItsSubject() throws Exception {
    TokenVerdict verdict = checker.check(signed(claims(ISSUER, AUDIENCE, issuedAgo(1))));

    assertThat(verdict).isEqualTo(new TokenVerdict.Accepted("fan-42"));
  }

  @Test
  void refusesARequestWithoutATokenAtStepZero() {
    assertThat(refusal(checker.check(""))).isEqualTo("0 missing");
  }

  @Test
  void refusesATokenWithTwoPartsAtStepOne() {
    assertThat(refusal(checker.check("abc.def"))).isEqualTo("1 malformed");
  }

  @Test
  void refusesAHeaderThatIsNotBase64urlAtStepTwo() {
    assertThat(refusal(checker.check("a*b.def.ghi"))).isEqualTo("2 malformed");
  }

  @Test
  void refusesAnUnsignedTokenAtStepThree() {
    String token = TokenMint.unsigned(claims(ISSUER, AUDIENCE, issuedAgo(1)));

    assertThat(refusal(checker.check(token))).isEqualTo("3 unsigned");
  }

  @Test
  void refusesAnHmacTokenKeyedWithThePublicKeyAtStepThree() throws Exception {
    String token =
        TokenMint.hs256(
            HEADER.replace("ES256", "HS256"),
            claims(ISSUER, AUDIENCE, issuedAgo(1)),
            issuerKeys.getPublic().getEncoded());

    assertThat(refusal(checker.check(token))).isEqualTo("3 algorithm");
  }

  @Test
  void refusesAKeyIdThatIsNotInTheKeySetAtStepFour() throws Exception {
    String token =
        TokenMint.es256(
            HEADER.replace(KEY_ID, "unknown-key"),
            claims(ISSUER, AUDIENCE, issuedAgo(1)),
            issuerKeys.getPrivate());

    assertThat(refusal(checker.check(token))).isEqualTo("4 signature");
  }

  @Test
  void refusesATokenSignedWithAnotherKeyAtStepFive() throws Exception {
    String token =
        TokenMint.es256(HEADER, claims(ISSUER, AUDIENCE, issuedAgo(1)), otherKeys.getPrivate());

    assertThat(refusal(checker.check(token))).isEqualTo("5 signature");
  }

  @Test
  void refusesAnotherIssuerAtStepSix() throws Exception {
    assertThat(
            refusal(checker.check(signed(claims("http://evil.example", AUDIENCE, issuedAgo(1))))))
        .isEqualTo("6 wrong-issuer");
  }

  @Test
  void refusesAnotherAudienceAtStepSix() throws Exception {
    assertThat(refusal(checker.check(signed(claims(ISSUER, "partner-api", issuedAgo(1))))))
        .isEqualTo("6 wrong-audience");
  }

  @Test
  void refusesATokenThatExpiredBeyondTheSkew() throws Exception {
    Instant issued = NOW.minus(LIFETIME).minus(OUTSIDE_SKEW);

    assertThat(refusal(checker.check(signed(claims(ISSUER, AUDIENCE, issued)))))
        .isEqualTo("6 expired");
  }

  @Test
  void acceptsATokenThatExpiredWithinTheSkew() throws Exception {
    Instant issued = NOW.minus(LIFETIME).minus(INSIDE_SKEW);

    assertThat(checker.check(signed(claims(ISSUER, AUDIENCE, issued))))
        .isInstanceOf(TokenVerdict.Accepted.class);
  }

  @Test
  void refusesATokenNotYetValidBeyondTheSkew() throws Exception {
    Instant issued = NOW.plus(OUTSIDE_SKEW);

    assertThat(refusal(checker.check(signed(claims(ISSUER, AUDIENCE, issued)))))
        .isEqualTo("6 not-yet-valid");
  }

  @Test
  void refusesATokenWithoutASubject() throws Exception {
    String claims = claims(ISSUER, AUDIENCE, issuedAgo(1)).replace("\"sub\":\"fan-42\",", "");

    assertThat(refusal(checker.check(signed(claims)))).isEqualTo("6 missing-claim");
  }

  @Test
  void refusesAnExpiryThatIsNotANumberAsAMissingClaimInsteadOfFailing() throws Exception {
    String claims =
        claims(ISSUER, AUDIENCE, issuedAgo(1)).replaceAll("\"exp\":[0-9]+", "\"exp\":\"soon\"");

    assertThat(refusal(checker.check(signed(claims)))).isEqualTo("6 missing-claim");
  }

  private static Instant issuedAgo(int minutes) {
    return NOW.minus(Duration.ofMinutes(minutes));
  }

  private static String signed(String claims) throws Exception {
    return TokenMint.es256(HEADER, claims, issuerKeys.getPrivate());
  }

  private static String claims(String issuer, String audience, Instant issuedAt) {
    return String.format(
        Locale.ROOT,
        "{\"iss\":\"%s\",\"aud\":\"%s\",\"sub\":\"fan-42\",\"iat\":%d,\"nbf\":%d,\"exp\":%d}",
        issuer,
        audience,
        issuedAt.getEpochSecond(),
        issuedAt.getEpochSecond(),
        issuedAt.plus(LIFETIME).getEpochSecond());
  }

  private static String refusal(TokenVerdict verdict) {
    return switch (verdict) {
      case TokenVerdict.Accepted accepted -> "accepted " + accepted.subject();
      case TokenVerdict.Refused refused -> refused.step() + " " + refused.reason();
    };
  }
}
