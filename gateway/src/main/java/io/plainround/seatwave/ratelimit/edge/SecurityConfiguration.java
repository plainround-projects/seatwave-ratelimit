package io.plainround.seatwave.ratelimit.edge;

import jakarta.servlet.DispatcherType;
import java.time.Clock;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;

/**
 * The module's one invariant lives here: no request reaches a booking route, or the mobile
 * backend-for-frontend, without a token whose signature, issuer, audience and expiry are valid.
 * Spring Security's filter chain runs before any route, so no route can skip it; anything not
 * listed is refused (deny by default), so a new route that nobody added here is closed, not open.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

  /**
   * The rules for every path the gateway serves.
   *
   * @param http Spring Security's builder
   * @param decoder the gateway's checked decoder
   * @param problems the writer of 401, 403 and 503 answers
   * @return the filter chain
   * @throws Exception when Spring Security cannot build the chain
   */
  @Bean
  SecurityFilterChain edge(HttpSecurity http, JwtDecoder decoder, ProblemResponses problems)
      throws Exception {
    http
        // Tokens travel in a header and the gateway sets no cookie, so a forged cross-site request
        // carries no credential: CSRF protection has nothing to protect here.
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(routes ->
            routes
                // Browsing the catalog needs no account.
                .requestMatchers(HttpMethod.GET, "/v1/events", "/v1/events/**")
                .permitAll()
                .requestMatchers("/v1/holds", "/v1/holds/**", "/v1/bookings", "/v1/bookings/**")
                .authenticated()
                .requestMatchers(HttpMethod.GET, "/mobile/v1/**")
                .authenticated()
                // Actuator answers only on the management port 8081, which Compose publishes on
                // 127.0.0.1 alone; on port 8080 these paths have no handler and answer 404.
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/metrics/**")
                .permitAll()
                // A 404 is written by forwarding to Boot's error page, and that forward passes the
                // chain again; without this rule deny-by-default turns every such 404 into a 401.
                // Only a request already admitted above can reach an error forward.
                .dispatcherTypeMatchers(DispatcherType.ERROR)
                .permitAll()
                .anyRequest()
                .denyAll())
        .oauth2ResourceServer(resourceServer ->
            resourceServer
                .jwt(jwt -> jwt.decoder(decoder))
                .authenticationEntryPoint(problems)
                .accessDeniedHandler(problems))
        .exceptionHandling(exceptions ->
            exceptions.authenticationEntryPoint(problems).accessDeniedHandler(problems));
    return http.build();
  }

  /**
   * The decoder: header rules first, then the signature against the issuer's key set, then the
   * claim rules.
   *
   * @param settings the issuer, audience, skew and key-set timeouts
   * @param clock the gateway's clock
   * @return the decoder Spring Security uses for every bearer token
   */
  @Bean
  JwtDecoder jwtDecoder(GatewayProperties settings, Clock clock) {
    // A key-set fetch is an outbound call, so it has its own connect and read timeouts, far below
    // the library's 30 s default: a slow issuer must not hold a fan's request for half a minute.
    SimpleClientHttpRequestFactory keySetHttp = new SimpleClientHttpRequestFactory();
    keySetHttp.setConnectTimeout(settings.keySetConnectTimeout());
    keySetHttp.setReadTimeout(settings.keySetReadTimeout());
    // Spring Security keeps the fetched key set for 5 minutes and fetches it again when a token
    // names a key id it does not hold, which is how a rotated key is picked up.
    NimbusJwtDecoder verifier =
        NimbusJwtDecoder.withJwkSetUri(settings.jwkSetUri().toString())
            .jwsAlgorithm(SignatureAlgorithm.ES256)
            .restOperations(new RestTemplate(keySetHttp))
            .build();
    verifier.setJwtValidator(
        new TokenClaimsRules(settings.issuer(), settings.audience(), settings.clockSkew(), clock));
    return new CheckedJwtDecoder(verifier);
  }

  /**
   * Puts the identity filter right after Spring Security, before the routes.
   *
   * @return the registration
   */
  @Bean
  FilterRegistrationBean<IdentityHeaderFilter> identityHeaderFilter() {
    FilterRegistrationBean<IdentityHeaderFilter> registration =
        new FilterRegistrationBean<>(new IdentityHeaderFilter());
    registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
    return registration;
  }
}
