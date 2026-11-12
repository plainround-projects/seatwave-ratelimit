package io.plainround.seatwave.ratelimit.edge;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The gateway's token settings, bound from {@code seatwave.gateway.*} and checked at start-up;
 * Spring Boot reports every broken rule together and the gateway does not start.
 *
 * @param issuer the exact {@code iss} the gateway trusts
 * @param audience the {@code aud} every token must name
 * @param jwkSetUri where the issuer publishes its public keys (RFC 7517)
 * @param clockSkew the allowance between the issuer's clock and the gateway's, 0 to 60 s
 * @param keySetConnectTimeout how long a key-set fetch may wait to connect
 * @param keySetReadTimeout how long a key-set fetch may wait for the answer
 */
@Validated
@ConfigurationProperties("seatwave.gateway")
public record GatewayProperties(
    @NotNull @Pattern(regexp = "https?://[A-Za-z0-9.:-]{1,200}") String issuer,
    @NotNull @Pattern(regexp = "[a-z0-9-]{1,64}") String audience,
    @NotNull URI jwkSetUri,
    @NotNull Duration clockSkew,
    @NotNull Duration keySetConnectTimeout,
    @NotNull Duration keySetReadTimeout) {

  // The largest skew the gateway accepts: more would let a token live a minute past its expiry.
  private static final Duration MAX_SKEW = Duration.ofSeconds(60);

  // Each rule below holds when its key is missing, because the missing key has its own problem
  // line; Boot reports every broken rule together, as settings problems, never as a stack trace.

  /**
   * The skew must be from 0 to 60 s.
   *
   * @return true when the skew is inside that range or missing
   */
  @AssertTrue(message = "clockSkew must be from 0 to 60s")
  public boolean isClockSkewInRange() {
    return clockSkew == null || (!clockSkew.isNegative() && clockSkew.compareTo(MAX_SKEW) <= 0);
  }

  /**
   * Both key-set timeouts must be positive.
   *
   * @return true when both are positive or missing
   */
  @AssertTrue(message = "keySetConnectTimeout and keySetReadTimeout must be positive")
  public boolean isEveryTimeoutPositive() {
    return (keySetConnectTimeout == null || keySetConnectTimeout.isPositive())
        && (keySetReadTimeout == null || keySetReadTimeout.isPositive());
  }
}
