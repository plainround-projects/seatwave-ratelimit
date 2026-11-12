package io.plainround.seatwave.ratelimit.bff;

/**
 * The catalog could not give the event or its seats in time. It is an infrastructure failure, so it
 * is an exception; the controller turns it into one 503 answer.
 */
public final class CatalogUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the failure.
   *
   * @param cause what the catalog call threw
   */
  public CatalogUnavailableException(Throwable cause) {
    super("the catalog did not answer in time: " + cause, cause);
  }
}
