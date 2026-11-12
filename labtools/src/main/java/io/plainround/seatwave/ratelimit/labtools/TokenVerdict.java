package io.plainround.seatwave.ratelimit.labtools;

import java.util.Objects;

/**
 * What {@link TokenChecker} decided about one token: accepted for a subject, or refused at one step
 * for one reason.
 */
sealed interface TokenVerdict {

  /**
   * The token passed every step.
   *
   * @param subject the verified {@code sub}, the identity the gateway passes inward
   */
  record Accepted(String subject) implements TokenVerdict {
    public Accepted {
      Objects.requireNonNull(subject, "subject");
    }
  }

  /**
   * The token failed one step.
   *
   * @param step the step that refused it, 0 to 6
   * @param reason the refusal's code, as the gateway's metric labels it
   * @param detail what exactly was wrong
   */
  record Refused(int step, String reason, String detail) implements TokenVerdict {
    public Refused {
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(detail, "detail");
    }
  }
}
