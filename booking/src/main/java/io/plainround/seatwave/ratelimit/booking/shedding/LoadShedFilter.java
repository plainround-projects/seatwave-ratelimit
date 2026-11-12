package io.plainround.seatwave.ratelimit.booking.shedding;

import io.micrometer.core.instrument.MeterRegistry;
import io.plainround.seatwave.ratelimit.limits.InFlightLimit;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Booking's load shedder: it admits a request only while fewer than {@code max-in-flight} are being
 * served by this instance, and answers every other one at once with {@code 503} and {@code
 * Retry-After}. It fires on booking's own saturation, whoever is sending, which is what the
 * gateway's per-fan limits cannot do: 200,000 fans each well under their limit can still be more
 * than booking can serve.
 *
 * <p>Thread-safe: the in-flight limit is its only state.
 */
public final class LoadShedFilter extends OncePerRequestFilter {

  private static final String SHED = "seatwave.booking.shed";
  private static final String REDUCED_CAPACITY =
      "https://iana.org/assignments/http-problem-types#temporary-reduced-capacity";

  private final InFlightLimit inFlight;
  private final Duration retryAfter;
  private final MeterRegistry meters;

  /**
   * Creates the shedder.
   *
   * @param settings the in-flight limit and the Retry-After to send
   * @param meters where the shed counter lives
   */
  public LoadShedFilter(SheddingProperties settings, MeterRegistry meters) {
    this.inFlight = new InFlightLimit(settings.maxInFlight());
    this.retryAfter = settings.retryAfter();
    this.meters = meters;
    meters.gauge("seatwave.booking.in.flight", inFlight, InFlightLimit::inFlight);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!inFlight.tryEnter()) {
      meters
          .counter(
              SHED, "path", request.getRequestURI().startsWith("/v1/holds") ? "holds" : "other")
          .increment();
      response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      response.setHeader("Retry-After", Long.toString(retryAfter.toSeconds()));
      response.setContentType("application/problem+json");
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response
          .getWriter()
          .write(
              String.format(
                  Locale.ROOT,
                  "{\"type\":\"%s\",\"title\":\"Booking is at its limit; nothing was"
                      + " held\",\"status\":503}",
                  REDUCED_CAPACITY));
      return;
    }
    try {
      chain.doFilter(request, response);
    } finally {
      // Every admitted request leaves exactly once, on the error path too.
      inFlight.leave();
    }
  }
}
