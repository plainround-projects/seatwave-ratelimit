package io.plainround.seatwave.ratelimit.edge;

import java.util.Objects;
import org.springframework.security.oauth2.jwt.BadJwtException;

/**
 * A token the gateway refused, with the one reason why. It extends {@link BadJwtException}, so
 * Spring Security turns it into a 401 like every other bad token; {@link ProblemResponses} reads
 * the reason back from it.
 */
public final class RefusedTokenException extends BadJwtException {

  private static final long serialVersionUID = 1L;

  /** The reason, kept for the problem body and the rejection counter. */
  private final TokenRefusal refusal;

  /**
   * Creates the refusal.
   *
   * @param refusal why the token was refused
   * @param cause the library's own exception, or the reason's name when the gateway found it alone
   */
  public RefusedTokenException(TokenRefusal refusal, Throwable cause) {
    super("token refused: " + refusal.code(), cause);
    this.refusal = Objects.requireNonNull(refusal, "refusal");
  }

  /**
   * Returns why the token was refused.
   *
   * @return the reason
   */
  public TokenRefusal refusal() {
    return refusal;
  }
}
