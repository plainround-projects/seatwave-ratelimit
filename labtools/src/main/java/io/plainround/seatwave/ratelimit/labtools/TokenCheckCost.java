package io.plainround.seatwave.ratelimit.labtools;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;

/**
 * Times the whole six-step check of one good ES256 token on one thread, so the module's "token
 * validation time" is a number from this machine rather than a guess. The signature verification
 * dominates; the parsing and claim checks are a few microseconds. Section 8 runs it with {@code
 * javac} and {@code java}.
 */
public final class TokenCheckCost {

  private static final Instant NOW = Instant.parse("2026-11-11T09:00:00Z");
  private static final String KEY_ID = "issuer-key-1";
  // Enough checks for the JIT to compile the path before timing, then enough to average over.
  private static final int WARM_UP_CHECKS = 20_000;
  private static final int TIMED_CHECKS = 20_000;
  private static final double NANOS_PER_MICRO = 1_000.0;
  private static final double MICROS_PER_SECOND = 1_000_000.0;

  private TokenCheckCost() {}

  /**
   * Runs the timing and prints the cost per check.
   *
   * @param arguments none
   * @throws GeneralSecurityException when the JDK lacks EC P-256, a setup error
   */
  public static void main(String[] arguments) throws GeneralSecurityException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair keys = generator.generateKeyPair();
    TokenChecker checker =
        new TokenChecker(
            Map.of(KEY_ID, keys.getPublic()),
            "http://issuer:8080",
            "seatwave-api",
            Duration.ofSeconds(30),
            Clock.fixed(NOW, ZoneOffset.UTC));
    long issued = NOW.minusSeconds(60).getEpochSecond();
    String claims =
        String.format(
            Locale.ROOT,
            "{\"iss\":\"http://issuer:8080\",\"aud\":\"seatwave-api\",\"sub\":\"fan-42\",\"iat\":%d,\"nbf\":%d,\"exp\":%d}",
            issued,
            issued,
            issued + Duration.ofMinutes(15).toSeconds());
    String token =
        TokenMint.es256(
            "{\"alg\":\"ES256\",\"typ\":\"JWT\",\"kid\":\"" + KEY_ID + "\"}",
            claims,
            keys.getPrivate());
    int accepted = 0;
    for (int check = 0; check < WARM_UP_CHECKS; check++) {
      accepted += checker.check(token) instanceof TokenVerdict.Accepted ? 1 : 0;
    }
    long start = System.nanoTime();
    for (int check = 0; check < TIMED_CHECKS; check++) {
      accepted += checker.check(token) instanceof TokenVerdict.Accepted ? 1 : 0;
    }
    double microsPerCheck = (System.nanoTime() - start) / NANOS_PER_MICRO / TIMED_CHECKS;
    System.out.printf(
        Locale.ROOT,
        "%d checks timed after %d warm-up checks, %d of %d accepted%n",
        TIMED_CHECKS,
        WARM_UP_CHECKS,
        accepted,
        WARM_UP_CHECKS + TIMED_CHECKS);
    System.out.printf(
        Locale.ROOT,
        "one ES256 token check: %.1f microseconds on one thread, about %.0f checks per core-second%n",
        microsPerCheck,
        MICROS_PER_SECOND / microsPerCheck);
  }
}
