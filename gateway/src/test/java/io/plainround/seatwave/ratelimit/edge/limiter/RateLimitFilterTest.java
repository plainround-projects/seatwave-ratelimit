package io.plainround.seatwave.ratelimit.edge.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The filter's decisions, one rule per test, with Redis replaced by {@link ScriptedRedis}: what
 * passes, what is refused and with which headers, which key a request takes from, and what each
 * policy does when the store fails.
 */
class RateLimitFilterTest {

  private static final Duration QUARANTINE = Duration.ofSeconds(1);
  // spring.data.redis.timeout in application.yaml.
  private static final Duration STORE_TIMEOUT = Duration.ofMillis(50);
  // One reconnection attempt to a stopped Redis, as the lab measured it (about 52 ms), made longer
  // here so the test cannot pass by luck: twenty in a row would take 4 s.
  private static final Duration ONE_RECONNECT = Duration.ofMillis(200);
  private static final int BURST = 20;
  // Far above the 50 ms bound and far below the 4 s of twenty reconnections in a row.
  private static final Duration SLOWEST_ACCEPTABLE = Duration.ofSeconds(1);
  private static final int GATEWAY_INSTANCES = 3;
  // The three policies of application.yaml, with the same numbers.
  private static final LimitPolicy HOLDS =
      new LimitPolicy(
          "holds",
          "POST",
          "/v1/holds",
          LimitPolicy.KeySource.SUBJECT,
          5,
          1,
          Duration.ofSeconds(10),
          LimitPolicy.StoreDown.REFUSE,
          false);
  private static final LimitPolicy SEAT_READS =
      new LimitPolicy(
          "seat-reads",
          "GET",
          "/v1/events",
          LimitPolicy.KeySource.ADDRESS,
          40,
          20,
          Duration.ofSeconds(1),
          LimitPolicy.StoreDown.LOCAL,
          false);
  private static final LimitPolicy FAN_REQUESTS =
      new LimitPolicy(
          "fan-requests",
          "ANY",
          "/",
          LimitPolicy.KeySource.SUBJECT,
          30,
          10,
          Duration.ofSeconds(1),
          LimitPolicy.StoreDown.LOCAL,
          false);
  private static final String CLIENT_ADDRESS = "203.0.113.7";
  // The script's wait after a burst of 5, 0.5 s later: 9.5 s, which Retry-After rounds up to 10.
  private static final long WAIT_MILLIS = 9_500;
  // 40 tokens shared by 3 instances: one instance's fallback holds 40 / 3 = 13, rounded down.
  private static final int LOCAL_SHARE = 13;

  private final ScriptedRedis redis = new ScriptedRedis();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final MovableClock clock = new MovableClock(Instant.parse("2026-11-12T09:00:00Z"));
  private final RedisTokenBucket shared = new RedisTokenBucket(redis, STORE_TIMEOUT);

  @AfterEach
  void stopTheStoreCalls() {
    shared.close();
  }

  @Test
  void passesAnAllowedRequestWithTheRateLimitFields() throws Exception {
    redis.allowWith(4);
    MockFilterChain chain = new MockFilterChain();

    MockHttpServletResponse response = run(filter(HOLDS), hold("fan-42"), chain);

    assertThat(chain.getRequest()).isNotNull();
    assertThat(response.getHeader("RateLimit-Policy")).isEqualTo("\"holds\";q=5;w=50");
    assertThat(response.getHeader("RateLimit")).isEqualTo("\"holds\";r=4");
    assertThat(outcomes("holds", "allowed")).isEqualTo(1);
  }

  @Test
  void refusesOverTheLimitWithRetryAfterRoundedUpToWholeSeconds() throws Exception {
    redis.refuseFor(WAIT_MILLIS);
    MockFilterChain chain = new MockFilterChain();

    MockHttpServletResponse response = run(filter(HOLDS), hold("fan-42"), chain);

    assertThat(chain.getRequest()).isNull();
    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getHeader("Retry-After")).isEqualTo("10");
    assertThat(response.getHeader("RateLimit")).isEqualTo("\"holds\";r=0;t=10");
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(response.getContentAsString())
        .contains("http-problem-types#quota-exceeded", "\"violated-policies\":[\"holds\"]");
  }

  @Test
  void sendsTheShapeOfThePolicyToTheStoreUnderTheVerifiedFansKey() throws Exception {
    run(filter(HOLDS), hold("fan-42"), new MockFilterChain());

    assertThat(redis.calls())
        .containsExactly(List.of("seatwave:rl:holds:sub:fan-42", "5", "1", "10000"));
  }

  @Test
  void keysByAddressWhenThePolicySaysSoEvenForAVerifiedFan() throws Exception {
    MockHttpServletRequest read = request("GET", "/v1/events/1/seats", "fan-42");

    run(filter(SEAT_READS), read, new MockFilterChain());

    assertThat(redis.calls().getFirst().getFirst())
        .isEqualTo("seatwave:rl:seat-reads:addr:" + CLIENT_ADDRESS);
  }

  @Test
  void neverUsesASubjectThatIsNotAModuleElevenSubjectAsAKey() throws Exception {
    run(filter(FAN_REQUESTS), request("GET", "/v1/holds", "fan 42; DROP"), new MockFilterChain());

    assertThat(redis.calls().getFirst().getFirst())
        .isEqualTo("seatwave:rl:fan-requests:addr:" + CLIENT_ADDRESS);
  }

  @Test
  void refusesAHoldWith503WhenTheStoreCannotAnswer() throws Exception {
    redis.failWith(new RedisConnectionFailureException("connection refused"));
    MockFilterChain chain = new MockFilterChain();

    MockHttpServletResponse response = run(filter(HOLDS), hold("fan-42"), chain);

    assertThat(chain.getRequest()).isNull();
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    assertThat(response.getContentAsString()).contains("problems/limit-unavailable");
    assertThat(meters.counter("seatwave.ratelimit.store.errors").count()).isEqualTo(1);
    assertThat(outcomes("holds", "store-down-refused")).isEqualTo(1);
  }

  @Test
  void decidesALocalPolicyWithThisInstancesThirdWhenTheStoreCannotAnswer() throws Exception {
    redis.failWith(new RedisConnectionFailureException("connection refused"));
    RateLimitFilter filter = filter(SEAT_READS);
    int passed = 0;

    for (int requestIndex = 0; requestIndex < SEAT_READS.capacity(); requestIndex++) {
      MockFilterChain chain = new MockFilterChain();
      run(filter, request("GET", "/v1/events/1/seats", ""), chain);
      passed += chain.getRequest() == null ? 0 : 1;
    }

    assertThat(passed).isEqualTo(LOCAL_SHARE);
    assertThat(outcomes("seat-reads", "allowed-local")).isEqualTo(LOCAL_SHARE);
  }

  @Test
  void asksTheStoreAgainOnlyAfterTheQuarantine() throws Exception {
    redis.failWith(new RedisConnectionFailureException("connection refused"));
    RateLimitFilter filter = filter(FAN_REQUESTS);
    run(filter, request("GET", "/v1/holds", "fan-42"), new MockFilterChain());
    run(filter, request("GET", "/v1/holds", "fan-42"), new MockFilterChain());
    assertThat(redis.calls()).hasSize(1);

    clock.advance(QUARANTINE);
    redis.allowWith(29);
    run(filter, request("GET", "/v1/holds", "fan-42"), new MockFilterChain());

    assertThat(redis.calls()).hasSize(2);
    assertThat(outcomes("fan-requests", "allowed")).isEqualTo(1);
  }

  @Test
  void asksTheStoreOnceWhenABurstArrivesRightAfterAQuarantine() throws Exception {
    redis.failWith(new RedisConnectionFailureException("connection refused"));
    RateLimitFilter filter = filter(FAN_REQUESTS);
    run(filter, request("GET", "/v1/holds", "fan-42"), new MockFilterChain());
    clock.advance(QUARANTINE);
    redis.reconnectOneAtATime(ONE_RECONNECT);

    try (ExecutorService fans = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int fan = 0; fan < BURST; fan++) {
        fans.submit(
            () -> run(filter, request("GET", "/v1/holds", "fan-42"), new MockFilterChain()));
      }
    }

    // The failure before the quarantine, and one probe after it; the other 19 did not ask.
    assertThat(redis.calls()).hasSize(2);
  }

  @Test
  void answersEveryRequestOfABurstWithinTheStoreTimeoutWhileRedisReconnects() throws Exception {
    redis.reconnectOneAtATime(ONE_RECONNECT);
    RateLimitFilter filter = filter(HOLDS);
    List<Future<MockHttpServletResponse>> answers = new ArrayList<>();
    long started = System.nanoTime();

    try (ExecutorService fans = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int fan = 0; fan < BURST; fan++) {
        answers.add(fans.submit(() -> run(filter, hold("fan-42"), new MockFilterChain())));
      }
    }

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(SLOWEST_ACCEPTABLE);
    for (Future<MockHttpServletResponse> answer : answers) {
      assertThat(answer.get().getStatus()).isEqualTo(503);
    }
  }

  @Test
  void letsARefusalPassInShadowModeAndCountsIt() throws Exception {
    redis.refuseFor(WAIT_MILLIS);
    MockFilterChain chain = new MockFilterChain();

    MockHttpServletResponse response = run(filter(shadow(HOLDS)), hold("fan-42"), chain);

    assertThat(chain.getRequest()).isNotNull();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(outcomes("holds", "shadow-refused")).isEqualTo(1);
  }

  @Test
  void letsAnUncheckedHoldPassInShadowModeAndCountsIt() throws Exception {
    redis.failWith(new RedisConnectionFailureException("connection refused"));
    MockFilterChain chain = new MockFilterChain();

    run(filter(shadow(HOLDS)), hold("fan-42"), chain);

    assertThat(chain.getRequest()).isNotNull();
    assertThat(outcomes("holds", "shadow-store-down")).isEqualTo(1);
  }

  @Test
  void timesEveryStoreCallUnderItsPolicy() throws Exception {
    run(filter(HOLDS), hold("fan-42"), new MockFilterChain());

    assertThat(meters.timer("seatwave.ratelimit.added.latency", "policy", "holds").count())
        .isEqualTo(1);
  }

  // The policy under test first, then the catch-all that RateLimitProperties requires.
  private RateLimitFilter filter(LimitPolicy policy) {
    List<LimitPolicy> policies =
        policy.pathPrefix().equals("/") ? List.of(policy) : List.of(policy, FAN_REQUESTS);
    RateLimitProperties settings = new RateLimitProperties(policies, GATEWAY_INSTANCES, QUARANTINE);
    return new RateLimitFilter(
        settings, shared, new LocalBuckets(GATEWAY_INSTANCES, clock), meters, clock);
  }

  private static MockHttpServletResponse run(
      RateLimitFilter filter, MockHttpServletRequest request, MockFilterChain chain)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  private static MockHttpServletRequest hold(String subject) {
    return request("POST", "/v1/holds", subject);
  }

  private static MockHttpServletRequest request(String method, String path, String subject) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setRemoteAddr(CLIENT_ADDRESS);
    if (!subject.isEmpty()) {
      request.addHeader("X-SeatWave-Subject", subject);
    }
    return request;
  }

  private double outcomes(String policyName, String outcome) {
    return meters
        .counter("seatwave.ratelimit.decisions", "policy", policyName, "outcome", outcome)
        .count();
  }

  private static LimitPolicy shadow(LimitPolicy policy) {
    return new LimitPolicy(
        policy.name(),
        policy.method(),
        policy.pathPrefix(),
        policy.key(),
        policy.capacity(),
        policy.refillTokens(),
        policy.refillPeriod(),
        policy.whenStoreDown(),
        true);
  }
}
