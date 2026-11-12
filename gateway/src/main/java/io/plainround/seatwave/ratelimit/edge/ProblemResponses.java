package io.plainround.seatwave.ratelimit.edge;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Writes every refusal of the gateway's security as problem details (RFC 9457), counts token
 * refusals by reason, and logs one line per refusal with the id it also gives the client. It never
 * logs or echoes the token. Thread-safe: it holds only the shared, thread-safe meter registry.
 */
@Component
public final class ProblemResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ProblemResponses.class);
  private static final String REJECTIONS = "seatwave.gateway.token.rejections";
  private static final String PROBLEMS = "https://seatwave.example/problems/";

  private final MeterRegistry meters;

  /**
   * Creates the writer.
   *
   * @param meters where the rejection counter lives
   */
  public ProblemResponses(MeterRegistry meters) {
    this.meters = meters;
  }

  /**
   * Answers a request without a valid token: 401, or 503 when the issuer's key set could not be
   * fetched, because then the client should retry, not sign in again.
   */
  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
      throws IOException {
    TokenRefusal refusal = refusalIn(failure);
    meters.counter(REJECTIONS, "reason", refusal.code()).increment();
    if (refusal == TokenRefusal.KEY_SET_UNAVAILABLE) {
      response.setHeader("Retry-After", "1");
      write(request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, refusal);
      return;
    }
    // RFC 6750: no error code when no token was sent, "invalid_token" when one was refused.
    response.setHeader(
        "WWW-Authenticate",
        refusal == TokenRefusal.MISSING ? "Bearer" : "Bearer error=\"invalid_token\"");
    write(request, response, HttpServletResponse.SC_UNAUTHORIZED, refusal);
  }

  /** Answers a request with a valid token for a path the gateway does not route: 403. */
  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
      throws IOException {
    String instance = "urn:uuid:" + UUID.randomUUID();
    LOG.warn(
        "request refused: no route allows {} {}; instance {}",
        request.getMethod(),
        request.getRequestURI(),
        instance);
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType("application/problem+json");
    response
        .getWriter()
        .write(
            String.format(
                Locale.ROOT,
                "{\"type\":\"%snot-routed\",\"title\":\"No route allows this request\",\"status\":403,\"instance\":\"%s\"}",
                PROBLEMS,
                instance));
  }

  private static TokenRefusal refusalIn(AuthenticationException failure) {
    // Spring Security wraps the decoder's exception; walk the causes to find the gateway's reason.
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof RefusedTokenException refused) {
        return refused.refusal();
      }
    }
    // No refused token anywhere: the request carried no bearer token at all.
    return TokenRefusal.MISSING;
  }

  private static void write(
      HttpServletRequest request, HttpServletResponse response, int status, TokenRefusal refusal)
      throws IOException {
    String instance = "urn:uuid:" + UUID.randomUUID();
    LOG.warn(
        "token refused: {} on {} {}; instance {}",
        refusal.code(),
        request.getMethod(),
        request.getRequestURI(),
        instance);
    response.setStatus(status);
    response.setContentType("application/problem+json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(
            String.format(
                Locale.ROOT,
                "{\"type\":\"%stoken-refused\",\"title\":\"The request's token was refused\",\"status\":%d,\"reason\":\"%s\",\"instance\":\"%s\"}",
                PROBLEMS,
                status,
                refusal.code(),
                instance));
  }
}
