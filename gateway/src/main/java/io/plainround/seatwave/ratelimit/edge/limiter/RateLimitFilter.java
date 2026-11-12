package io.plainround.seatwave.ratelimit.edge.limiter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.plainround.seatwave.ratelimit.limits.Decision;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The gateway's rate limiter. It runs for every request after Spring Security and the identity
 * filter of Module 11, so {@code X-SeatWave-Subject} here is always the verified fan or absent;
 * before any route, so a refused request never reaches a service. For each request it finds the
 * first policy that covers it, builds the limit key, takes one token from the shared bucket in
 * Redis, and either passes the request on with the RateLimit headers or answers {@code 429} with
 * {@code Retry-After}. When Redis cannot answer, the policy decides: refuse with {@code 503}, or
 * decide with this instance's local share.
 *
 * <p>Thread-safe: its fields are immutable or atomic.
 */
public final class RateLimitFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(RateLimitFilter.class);
  private static final String SUBJECT_HEADER = "X-SeatWave-Subject";
  // Module 11's subjects; anything else is not used as a key, so a key cannot grow without bound.
  private static final Pattern SUBJECT = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final String DECISIONS = "seatwave.ratelimit.decisions";
  private static final String ADDED_LATENCY = "seatwave.ratelimit.added.latency";
  private static final String STORE_ERRORS = "seatwave.ratelimit.store.errors";
  private static final String QUOTA_EXCEEDED =
      "https://iana.org/assignments/http-problem-types#quota-exceeded";
  private static final String LIMIT_UNAVAILABLE =
      "https://seatwave.example/problems/limit-unavailable";
  private static final int TOO_MANY_REQUESTS = 429;
  private static final long MILLIS_PER_SECOND = 1_000;

  private final RateLimitProperties settings;
  private final RedisTokenBucket shared;
  private final LocalBuckets local;
  private final MeterRegistry meters;
  private final Clock clock;
  // Until this time, this instance does not ask Redis at all (see storeQuarantine); 0 while the
  // store answers.
  private final AtomicLong storeQuarantinedUntilMillis = new AtomicLong();

  /**
   * Creates the filter.
   *
   * @param settings the policies and the fallback settings
   * @param shared the Redis bucket
   * @param local this instance's fallback buckets
   * @param meters where the limiter's metrics live
   * @param clock this instance's clock, for the quarantine only
   */
  public RateLimitFilter(
      RateLimitProperties settings,
      RedisTokenBucket shared,
      LocalBuckets local,
      MeterRegistry meters,
      Clock clock) {
    this.settings = settings;
    this.shared = shared;
    this.local = local;
    this.meters = meters;
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    LimitPolicy policy = settings.policyFor(request.getMethod(), request.getRequestURI());
    String limitKey = limitKey(policy, request);
    Timer.Sample started = Timer.start(meters);
    Optional<Decision> fromStore = askStore(policy, limitKey);
    started.stop(meters.timer(ADDED_LATENCY, "policy", policy.name()));

    if (fromStore.isEmpty() && policy.whenStoreDown() == LimitPolicy.StoreDown.REFUSE) {
      count(policy, policy.isShadow() ? "shadow-store-down" : "store-down-refused");
      if (!policy.isShadow()) {
        refuseUnchecked(request, response, policy);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    Decision decision = fromStore.orElseGet(() -> local.take(policy, limitKey));
    writeRateLimitHeaders(response, policy, decision);
    if (decision.isAllowed()) {
      count(policy, fromStore.isPresent() ? "allowed" : "allowed-local");
      chain.doFilter(request, response);
      return;
    }
    if (policy.isShadow()) {
      // Shadow mode: the limit is measured on real traffic before it is allowed to refuse anyone.
      count(policy, "shadow-refused");
      LOG.info(
          "would refuse {} {} under policy {}",
          request.getMethod(),
          request.getRequestURI(),
          policy.name());
      chain.doFilter(request, response);
      return;
    }
    count(policy, "refused");
    refuseOverLimit(request, response, policy, decision);
  }

  // The fan's verified id when the policy keys by subject and the gateway verified one; otherwise
  // the client's address. With server.forward-headers-strategy=native, Tomcat has already replaced
  // the balancer's address with the client's from X-Forwarded-For, and only when the request came
  // from a trusted balancer address (server.tomcat.remoteip.internal-proxies).
  private static String limitKey(LimitPolicy policy, HttpServletRequest request) {
    String subject = request.getHeader(SUBJECT_HEADER);
    boolean hasSubject = subject != null && SUBJECT.matcher(subject).matches();
    if (policy.key() == LimitPolicy.KeySource.SUBJECT && hasSubject) {
      return "sub:" + subject;
    }
    return "addr:" + request.getRemoteAddr();
  }

  private Optional<Decision> askStore(LimitPolicy policy, String limitKey) {
    long nowMillis = clock.millis();
    long quarantinedUntilMillis = storeQuarantinedUntilMillis.get();
    if (quarantinedUntilMillis != 0) {
      if (nowMillis < quarantinedUntilMillis) {
        return Optional.empty();
      }
      // The quarantine is over. Only the request that wins this swap asks the store whether it is
      // back; every other request sees a new quarantine and decides without it, so a dead store is
      // asked once per quarantine per instance, however many requests arrive at that moment.
      long nextQuarantineMillis = nowMillis + settings.storeQuarantine().toMillis();
      if (!storeQuarantinedUntilMillis.compareAndSet(
          quarantinedUntilMillis, nextQuarantineMillis)) {
        return Optional.empty();
      }
    }
    try {
      Decision decision = shared.take(policy, limitKey);
      if (storeQuarantinedUntilMillis.get() != 0) {
        // The store answered: every request may ask it again.
        storeQuarantinedUntilMillis.set(0);
      }
      return Optional.of(decision);
    } catch (DataAccessException failure) {
      // Down, refusing or slower than spring.data.redis.timeout: stop asking for a moment, so a
      // dead store costs one timeout per quarantine instead of one per request.
      meters.counter(STORE_ERRORS).increment();
      storeQuarantinedUntilMillis.set(nowMillis + settings.storeQuarantine().toMillis());
      LOG.warn(
          "rate-limit store unavailable, deciding without it for {}: {}",
          settings.storeQuarantine(),
          failure.getMessage());
      return Optional.empty();
    }
  }

  private void count(LimitPolicy policy, String outcome) {
    meters.counter(DECISIONS, "policy", policy.name(), "outcome", outcome).increment();
  }

  // The IETF draft's fields (draft-ietf-httpapi-ratelimit-headers-11, still a draft): the policy
  // as quota per window, and what is left now.
  private static void writeRateLimitHeaders(
      HttpServletResponse response, LimitPolicy policy, Decision decision) {
    response.setHeader(
        "RateLimit-Policy",
        String.format(
            Locale.ROOT,
            "\"%s\";q=%d;w=%d",
            policy.name(),
            policy.capacity(),
            policy.windowSeconds()));
    String limit =
        decision.isAllowed()
            ? String.format(Locale.ROOT, "\"%s\";r=%d", policy.name(), decision.remaining())
            : String.format(
                Locale.ROOT, "\"%s\";r=0;t=%d", policy.name(), retryAfterSeconds(decision));
    response.setHeader("RateLimit", limit);
  }

  // Retry-After carries whole seconds (RFC 9110), so the wait is rounded up: rounding down would
  // send the client back before a token exists, into a second refusal.
  private static long retryAfterSeconds(Decision decision) {
    return Math.ceilDiv(decision.retryAfterMillis(), MILLIS_PER_SECOND);
  }

  private static void refuseOverLimit(
      HttpServletRequest request,
      HttpServletResponse response,
      LimitPolicy policy,
      Decision decision)
      throws IOException {
    response.setHeader("Retry-After", Long.toString(retryAfterSeconds(decision)));
    writeProblem(
        request,
        response,
        TOO_MANY_REQUESTS,
        String.format(
            Locale.ROOT,
            "{\"type\":\"%s\",\"title\":\"Too many requests under policy %s\",\"status\":429,"
                + "\"violated-policies\":[\"%s\"],\"instance\":\"%s\"}",
            QUOTA_EXCEEDED,
            policy.name(),
            policy.name(),
            "urn:uuid:" + UUID.randomUUID()));
  }

  // The store could not say whether this caller is under the limit, and this policy may not guess:
  // 503, because the client did nothing wrong, with the quarantine as the wait.
  private void refuseUnchecked(
      HttpServletRequest request, HttpServletResponse response, LimitPolicy policy)
      throws IOException {
    response.setHeader(
        "Retry-After", Long.toString(Math.max(1, settings.storeQuarantine().toSeconds())));
    writeProblem(
        request,
        response,
        HttpServletResponse.SC_SERVICE_UNAVAILABLE,
        String.format(
            Locale.ROOT,
            "{\"type\":\"%s\",\"title\":\"The limit for %s cannot be checked right now\","
                + "\"status\":503,\"instance\":\"%s\"}",
            LIMIT_UNAVAILABLE,
            policy.name(),
            "urn:uuid:" + UUID.randomUUID()));
  }

  private static void writeProblem(
      HttpServletRequest request, HttpServletResponse response, int status, String body)
      throws IOException {
    LOG.info("refused {} {} with {}", request.getMethod(), request.getRequestURI(), status);
    response.setStatus(status);
    response.setContentType("application/problem+json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(body);
  }
}
