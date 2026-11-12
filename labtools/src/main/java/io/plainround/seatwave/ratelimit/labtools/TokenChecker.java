package io.plainround.seatwave.ratelimit.labtools;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The gateway's token check as six numbered steps, in the order the real gateway runs them ({@code
 * CheckedJwtDecoder}, then Nimbus's signature check, then {@code TokenClaimsRules}). Each step
 * trusts nothing that a later step has not checked: the header is read before the signature is
 * verified, so it may only choose a key, never the rules. Teaching code with the JDK alone.
 * Thread-safe: it holds only immutable values.
 */
final class TokenChecker {

  // The only algorithm the gateway allows (RFC 7518, ECDSA on P-256 with SHA-256).
  private static final String ALLOWED_ALGORITHM = "ES256";
  private static final int PARTS = 3;
  private static final Base64.Decoder BASE64URL = Base64.getUrlDecoder();
  // A NumericDate in whole seconds; 12 digits reach the year 33658, and a text or a longer number
  // is not a time this checker can compare, so it counts as a missing claim.
  private static final Pattern NUMERIC_DATE = Pattern.compile("[0-9]{1,12}");

  private final Map<String, PublicKey> keySet;
  private final String issuer;
  private final String audience;
  private final Duration clockSkew;
  private final Clock clock;

  TokenChecker(
      Map<String, PublicKey> keySet,
      String issuer,
      String audience,
      Duration clockSkew,
      Clock clock) {
    this.keySet = Map.copyOf(keySet);
    this.issuer = issuer;
    this.audience = audience;
    this.clockSkew = clockSkew;
    this.clock = clock;
  }

  /**
   * Walks one token through every step.
   *
   * @param token the text after {@code Bearer }, or empty when the request had none
   * @return accepted with the subject, or refused with the step and the reason
   */
  TokenVerdict check(String token) {
    if (token.isEmpty()) {
      return new TokenVerdict.Refused(0, "missing", "no Authorization: Bearer header");
    }
    String[] parts = token.split("\\.", -1);
    if (parts.length != PARTS) {
      return new TokenVerdict.Refused(1, "malformed", parts.length + " parts, not 3");
    }
    Optional<String> header = decode(parts[0]);
    if (header.isEmpty()) {
      return new TokenVerdict.Refused(2, "malformed", "the header is not base64url");
    }
    String algorithm = CompactJson.field(header.get(), "alg").orElse("");
    if (algorithm.equals("none")) {
      return new TokenVerdict.Refused(3, "unsigned", "alg is none: anybody could have written it");
    }
    if (!algorithm.equals(ALLOWED_ALGORITHM)) {
      return new TokenVerdict.Refused(
          3, "algorithm", "alg is " + algorithm + ", only ES256 is allowed");
    }
    String keyId = CompactJson.field(header.get(), "kid").orElse("");
    PublicKey key = keySet.get(keyId);
    if (key == null) {
      return new TokenVerdict.Refused(4, "signature", "no key " + keyId + " in the key set");
    }
    if (!verifies(parts[0] + "." + parts[1], parts[2], key)) {
      return new TokenVerdict.Refused(
          5, "signature", "the signature does not verify with key " + keyId);
    }
    // Only now are the claims trustworthy: the issuer's key signed exactly these bytes.
    return checkClaims(decode(parts[1]).orElse(""));
  }

  private TokenVerdict checkClaims(String claims) {
    Instant now = clock.instant();
    Optional<String> subject = CompactJson.field(claims, "sub");
    Optional<Instant> expiresAt = instant(claims, "exp");
    if (subject.isEmpty() || expiresAt.isEmpty() || instant(claims, "iat").isEmpty()) {
      return new TokenVerdict.Refused(6, "missing-claim", "sub, exp and iat are required");
    }
    if (!now.isBefore(expiresAt.get().plus(clockSkew))) {
      return new TokenVerdict.Refused(
          6,
          "expired",
          "exp "
              + expiresAt.get()
              + " + "
              + clockSkew.toSeconds()
              + " s skew is not after now "
              + now);
    }
    Optional<Instant> notBefore = instant(claims, "nbf");
    if (notBefore.isPresent() && now.isBefore(notBefore.get().minus(clockSkew))) {
      return new TokenVerdict.Refused(
          6,
          "not-yet-valid",
          "nbf " + notBefore.get() + " - " + clockSkew.toSeconds() + " s skew is after now " + now);
    }
    String tokenIssuer = CompactJson.field(claims, "iss").orElse("");
    if (!issuer.equals(tokenIssuer)) {
      return new TokenVerdict.Refused(
          6, "wrong-issuer", "iss is " + tokenIssuer + ", not " + issuer);
    }
    String tokenAudience = CompactJson.field(claims, "aud").orElse("");
    if (!audience.equals(tokenAudience)) {
      return new TokenVerdict.Refused(
          6, "wrong-audience", "aud is " + tokenAudience + ", not " + audience);
    }
    return new TokenVerdict.Accepted(subject.get());
  }

  private static Optional<Instant> instant(String claims, String name) {
    // JWT times are seconds since 1970 in UTC (RFC 7519, NumericDate).
    return CompactJson.field(claims, name)
        .filter(text -> NUMERIC_DATE.matcher(text).matches())
        .map(Long::parseLong)
        .map(Instant::ofEpochSecond);
  }

  private static Optional<String> decode(String part) {
    try {
      return Optional.of(new String(BASE64URL.decode(part), StandardCharsets.UTF_8));
    } catch (IllegalArgumentException notBase64url) {
      return Optional.empty();
    }
  }

  private static boolean verifies(String signingInput, String signature, PublicKey key) {
    try {
      Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
      verifier.initVerify(key);
      verifier.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return verifier.verify(BASE64URL.decode(signature));
    } catch (GeneralSecurityException | IllegalArgumentException unreadable) {
      // A signature that cannot even be read does not verify; nothing about it is trusted.
      return false;
    }
  }
}
