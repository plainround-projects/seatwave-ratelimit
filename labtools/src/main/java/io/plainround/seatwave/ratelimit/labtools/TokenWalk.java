package io.plainround.seatwave.ratelimit.labtools;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Walks one good token and the eight bad tokens of the module's invariant through {@link
 * TokenChecker} and prints, for each, the step that refused it. It also shows why the algorithm
 * rule exists: a verifier that let the token's header choose HS256 would accept a token forged with
 * the issuer's public key. Section 8 runs it with {@code javac} and {@code java}; {@code ./gradlew
 * -q :labtools:tokenWalk} runs the same class.
 */
public final class TokenWalk {

  private static final String ISSUER = "http://issuer:8080";
  private static final String AUDIENCE = "seatwave-api";
  private static final String KEY_ID = "issuer-key-1";
  // A fixed moment, so the output is the same on every run.
  private static final Instant NOW = Instant.parse("2026-11-11T09:00:00Z");
  // Section 7's brief: tokens live 15 minutes; the gateway allows 30 s of clock skew.
  private static final Duration LIFETIME = Duration.ofMinutes(15);
  private static final Duration SKEW = Duration.ofSeconds(30);
  private static final int NAME_WIDTH = 30;

  private TokenWalk() {}

  /**
   * Runs the walk and prints one line per token.
   *
   * @param arguments none
   * @throws GeneralSecurityException when the JDK lacks EC P-256 or HMAC-SHA256, a setup error
   */
  public static void main(String[] arguments) throws GeneralSecurityException {
    KeyPair issuerKeys = p256();
    KeyPair otherKeys = p256();
    TokenChecker checker =
        new TokenChecker(
            Map.of(KEY_ID, issuerKeys.getPublic()),
            ISSUER,
            AUDIENCE,
            SKEW,
            Clock.fixed(NOW, ZoneOffset.UTC));
    String header = "{\"alg\":\"ES256\",\"typ\":\"JWT\",\"kid\":\"" + KEY_ID + "\"}";
    Instant issued = NOW.minus(Duration.ofMinutes(1));
    Map<String, String> tokens = new LinkedHashMap<>();
    tokens.put(
        "good", TokenMint.es256(header, claims(ISSUER, AUDIENCE, issued), issuerKeys.getPrivate()));
    tokens.put(
        "1 wrong signature",
        TokenMint.es256(header, claims(ISSUER, AUDIENCE, issued), otherKeys.getPrivate()));
    tokens.put(
        "2 wrong issuer",
        TokenMint.es256(
            header, claims("http://evil.example", AUDIENCE, issued), issuerKeys.getPrivate()));
    tokens.put(
        "3 wrong audience",
        TokenMint.es256(header, claims(ISSUER, "partner-api", issued), issuerKeys.getPrivate()));
    // Issued 16 minutes ago with a 15-minute life: expired 60 s ago, beyond the 30 s skew.
    tokens.put(
        "4 expired",
        TokenMint.es256(
            header,
            claims(ISSUER, AUDIENCE, NOW.minus(Duration.ofMinutes(16))),
            issuerKeys.getPrivate()));
    // Valid only from 2 minutes from now, beyond the 30 s skew.
    tokens.put(
        "5 not yet valid",
        TokenMint.es256(
            header,
            claims(ISSUER, AUDIENCE, NOW.plus(Duration.ofMinutes(2))),
            issuerKeys.getPrivate()));
    tokens.put("6 alg none", TokenMint.unsigned(claims(ISSUER, AUDIENCE, issued)));
    byte[] publicKeyBytes = issuerKeys.getPublic().getEncoded();
    String confused =
        TokenMint.hs256(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"" + KEY_ID + "\"}",
            claims(ISSUER, AUDIENCE, issued),
            publicKeyBytes);
    tokens.put("7 HS256 keyed with public key", confused);
    tokens.put("8 missing", "");
    tokens.forEach((name, token) -> System.out.println(pad(name) + describe(checker.check(token))));
    // The attack the algorithm rule stops: the public key is public, so anyone can compute this
    // MAC.
    String[] parts = confused.split("\\.", -1);
    byte[] expected = TokenMint.hmac(parts[0] + "." + parts[1], publicKeyBytes);
    boolean naiveAccepts = Arrays.equals(expected, Base64.getUrlDecoder().decode(parts[2]));
    System.out.println(
        "a verifier that let the header pick HS256 and used the public key as its secret would accept token 7: "
            + naiveAccepts);
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

  private static String describe(TokenVerdict verdict) {
    return switch (verdict) {
      case TokenVerdict.Accepted accepted ->
          "accepted after steps 1 to 6: X-SeatWave-Subject: " + accepted.subject();
      case TokenVerdict.Refused refused ->
          "refused at step " + refused.step() + " (" + refused.reason() + "): " + refused.detail();
    };
  }

  private static String pad(String name) {
    return String.format(Locale.ROOT, "%-" + NAME_WIDTH + "s", name);
  }

  private static KeyPair p256() throws GeneralSecurityException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }
}
