package io.plainround.seatwave.ratelimit.edge;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The claim checks the gateway makes once the signature has verified: required claims, expiry,
 * not-before, issuer and audience, in that order. It replaces Spring Security's default validator
 * on purpose, so each rule is one visible line with its own reason and its own test. Thread-safe:
 * it holds only final values.
 */
public final class TokenClaimsRules implements OAuth2TokenValidator<Jwt> {

  private final String issuer;
  private final String audience;
  private final Duration clockSkew;
  private final Clock clock;

  /**
   * Creates the rules.
   *
   * @param issuer the only issuer the gateway trusts, compared exactly
   * @param audience the audience SeatWave's API must be named as
   * @param clockSkew how far the issuer's clock and the gateway's may disagree
   * @param clock the gateway's clock
   */
  public TokenClaimsRules(String issuer, String audience, Duration clockSkew, Clock clock) {
    this.issuer = Objects.requireNonNull(issuer, "issuer");
    this.audience = Objects.requireNonNull(audience, "audience");
    this.clockSkew = Objects.requireNonNull(clockSkew, "clockSkew");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Checks a token whose signature has already verified.
   *
   * @param token the decoded token
   * @return success, or a failure whose description is the {@link TokenRefusal} code
   */
  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    return refusal(token)
        .map(found ->
            OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", found.code(), "")))
        .orElseGet(OAuth2TokenValidatorResult::success);
  }

  /**
   * Finds the first rule the token breaks.
   *
   * @param token the decoded token
   * @return the first broken rule, or empty when the token passes all of them
   */
  public Optional<TokenRefusal> refusal(Jwt token) {
    Instant now = clock.instant();
    if (token.getSubject() == null || token.getExpiresAt() == null || token.getIssuedAt() == null) {
      return Optional.of(TokenRefusal.MISSING_CLAIM);
    }
    // The skew is a small allowance in both directions: two clocks never agree exactly.
    if (!now.isBefore(token.getExpiresAt().plus(clockSkew))) {
      return Optional.of(TokenRefusal.EXPIRED);
    }
    if (token.getNotBefore() != null && now.isBefore(token.getNotBefore().minus(clockSkew))) {
      return Optional.of(TokenRefusal.NOT_YET_VALID);
    }
    // getIssuer() turns the claim into a URL; the comparison is on the exact text the issuer wrote.
    if (!issuer.equals(token.getClaimAsString("iss"))) {
      return Optional.of(TokenRefusal.WRONG_ISSUER);
    }
    List<String> audiences = token.getAudience();
    if (audiences == null || !audiences.contains(audience)) {
      return Optional.of(TokenRefusal.WRONG_AUDIENCE);
    }
    return Optional.empty();
  }
}
