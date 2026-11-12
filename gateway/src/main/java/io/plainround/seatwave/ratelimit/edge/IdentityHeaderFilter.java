package io.plainround.seatwave.ratelimit.edge;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs after Spring Security and before the routes, for every request: it replaces whatever
 * identity headers the client sent with the identity the gateway verified (see {@link
 * IdentityRequest}). The services trust {@code X-SeatWave-Subject} because only this filter can set
 * it, and only the gateway can reach them (Section 10 of the module).
 */
public final class IdentityHeaderFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    Optional<String> subject =
        authentication instanceof JwtAuthenticationToken verified
            ? Optional.of(verified.getToken().getSubject())
            : Optional.empty();
    chain.doFilter(new IdentityRequest(request, subject), response);
  }
}
