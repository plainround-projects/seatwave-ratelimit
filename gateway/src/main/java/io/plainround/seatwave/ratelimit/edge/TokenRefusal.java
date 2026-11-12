package io.plainround.seatwave.ratelimit.edge;

import java.util.Locale;

/**
 * Every reason the gateway refuses a request's token, in the order the checks run. The reason is
 * the label of the {@code seatwave.gateway.token.rejections} counter and the {@code reason} of the
 * problem body, so it has a fixed, small set of values (a metric label must never be a user id or a
 * token).
 */
public enum TokenRefusal {
  /** No {@code Authorization: Bearer} header on a route that needs one. */
  MISSING,
  /** Not three base64url parts with JSON inside, or no key id in the header. */
  MALFORMED,
  /** {@code alg: none}: a token anybody can write, with no signature at all. */
  UNSIGNED,
  /** Signed with an algorithm the gateway does not allow, such as HS256 with the public key. */
  ALGORITHM,
  /** The signature does not verify with any key of the issuer's key set. */
  SIGNATURE,
  /** The key set cannot be fetched and no cached copy holds the token's key. */
  KEY_SET_UNAVAILABLE,
  /** A required claim ({@code sub}, {@code exp}, {@code iat}) is absent. */
  MISSING_CLAIM,
  /** {@code exp} is in the past, beyond the allowed clock skew. */
  EXPIRED,
  /** {@code nbf} is in the future, beyond the allowed clock skew. */
  NOT_YET_VALID,
  /** {@code iss} is not SeatWave's issuer. */
  WRONG_ISSUER,
  /** {@code aud} does not name SeatWave's API. */
  WRONG_AUDIENCE;

  /**
   * Returns the reason as it appears in metrics, logs and problem bodies, for example {@code
   * not-yet-valid}.
   *
   * @return the lower-case, hyphenated name
   */
  public String code() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
