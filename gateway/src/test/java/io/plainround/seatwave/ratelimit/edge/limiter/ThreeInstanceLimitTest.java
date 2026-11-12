package io.plainround.seatwave.ratelimit.edge.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The module's invariant, tested on the running stack: across all three gateway instances, one fan
 * is never allowed more than the bucket size plus the refill rate times the elapsed time. The test
 * plays Module 10's balancer itself, sending the fan's holds to the three instances in turn, so
 * every instance sees a third of them; an instance with its own bucket would allow 5 on its own.
 *
 * <p>Tagged "stack": it needs {@code docker compose -p seatwave-ratelimit up}, and {@code ./gradlew
 * check} never runs it.
 */
@Tag("stack")
class ThreeInstanceLimitTest {

  private static final List<URI> GATEWAYS =
      List.of(
          URI.create("http://127.0.0.1:21200"),
          URI.create("http://127.0.0.1:21201"),
          URI.create("http://127.0.0.1:21202"));
  private static final URI ISSUER = URI.create("http://127.0.0.1:21220");
  private static final int HOLDS = 60;
  // One hold every 400 ms, so the run lasts about 24 s and the bucket refills twice on the way.
  private static final Duration PACE = Duration.ofMillis(400);
  private static final long CAPACITY = 5;
  private static final double REFILL_PER_SECOND = 0.1;
  private static final long LONGEST_RETRY_AFTER_SECONDS = 10;
  // Enough rounds for the JIT to compile the hold path on each gateway's one CPU.
  private static final int WARM_UP_ROUNDS = 30;
  private static final int TOO_MANY_REQUESTS = 429;
  private static final int UNAVAILABLE = 503;
  private static final int OK = 200;
  private static final double NANOS_PER_SECOND = 1e9;
  private static final int FAN_NUMBER_BOUND = 1_000_000_000;
  private static final Pattern ACCESS_TOKEN =
      Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"");

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  // With Redis stopped every hold is 503, so this test has nothing to count; Lab 2 runs the other.
  @Test
  @DisabledIfSystemProperty(named = "seatwave.redis", matches = "stopped")
  void neverAllowsOneFanMoreThanTheBucketPlusRefillAcrossThreeInstances() throws Exception {
    warmUp(tokenFor("fan-" + new SecureRandom().nextInt(FAN_NUMBER_BOUND)));
    // A new fan on every run, so the run starts with a full bucket whatever ran before.
    String token = tokenFor("fan-" + new SecureRandom().nextInt(FAN_NUMBER_BOUND));
    List<HttpResponse<String>> answers = new ArrayList<>();
    long startedNanos = System.nanoTime();
    for (int holdIndex = 0; holdIndex < HOLDS; holdIndex++) {
      URI gateway = GATEWAYS.get(holdIndex % GATEWAYS.size());
      answers.add(hold(gateway, token, "A-" + (holdIndex + 1)));
      Thread.sleep(PACE);
    }
    double elapsedSeconds = (System.nanoTime() - startedNanos) / NANOS_PER_SECOND;

    long refused = answers.stream().filter(ThreeInstanceLimitTest::isOverTheLimit).count();
    long unchecked = answers.stream().filter(ThreeInstanceLimitTest::isUnchecked).count();
    long allowed = HOLDS - refused - unchecked;
    Map<Integer, Long> statuses =
        answers.stream()
            .collect(
                Collectors.groupingBy(
                    HttpResponse::statusCode, TreeMap::new, Collectors.counting()));
    System.out.println(
        String.format(
            Locale.ROOT,
            "holds %d, allowed %d, refused %d, elapsed %.1f s, bound %.1f, statuses %s",
            HOLDS,
            allowed,
            refused,
            elapsedSeconds,
            CAPACITY + REFILL_PER_SECOND * elapsedSeconds,
            statuses));

    assertThat(allowed).isLessThanOrEqualTo((long) (CAPACITY + REFILL_PER_SECOND * elapsedSeconds));
    // Not too tight either: the whole burst passes.
    assertThat(allowed).isGreaterThanOrEqualTo(CAPACITY);
    answers.stream()
        .filter(ThreeInstanceLimitTest::isOverTheLimit)
        .forEach(refusal -> {
          long retryAfter =
              Long.parseLong(refusal.headers().firstValue("Retry-After").orElseThrow());
          assertThat(retryAfter).isBetween(1L, LONGEST_RETRY_AFTER_SECONDS);
          assertThat(refusal.headers().firstValue("RateLimit"))
              .hasValueSatisfying(value -> assertThat(value).startsWith("\"holds\";r=0;t="));
        });
  }

  // Run by Lab 2 after "docker compose -p seatwave-ratelimit stop redis", with
  // -Dseatwave.redis=stopped: holds fail safe, seat reads keep working on the local share.
  @Test
  @EnabledIfSystemProperty(named = "seatwave.redis", matches = "stopped")
  void refusesHoldsButServesSeatReadsWhileRedisIsDown() throws Exception {
    String token = tokenFor("fan-" + new SecureRandom().nextInt(FAN_NUMBER_BOUND));
    HttpResponse<String> holdAnswer = hold(GATEWAYS.getFirst(), token, "B-1");
    HttpResponse<String> readAnswer =
        client.send(
            HttpRequest.newBuilder(GATEWAYS.getFirst().resolve("/v1/events/1/seats?section=A"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(holdAnswer.statusCode()).isEqualTo(UNAVAILABLE);
    assertThat(holdAnswer.headers().firstValue("Retry-After")).isPresent();
    assertThat(readAnswer.statusCode()).isEqualTo(OK);
  }

  // The limiter's own two refusals; every other answer passed it (201, 409 from booking, or a
  // failure of the gateway's call to booking after the limiter had allowed the hold), so counting
  // everything else as allowed can only make the upper bound harder to keep.
  private static boolean isOverTheLimit(HttpResponse<String> answer) {
    return answer.statusCode() == TOO_MANY_REQUESTS;
  }

  // A hold the limiter could not check (the store did not answer within 50 ms), refused with 503.
  private static boolean isUnchecked(HttpResponse<String> answer) {
    return answer.statusCode() == UNAVAILABLE && answer.body().contains("limit-unavailable");
  }

  // A gateway just started runs its first requests in the interpreter while its one CPU compiles;
  // the limiter's 50 ms bound on Redis can then refuse a hold as unchecked. A throwaway fan's holds
  // and some seat reads through every gateway first, so the counted run meets warm code.
  private void warmUp(String throwawayToken) throws IOException, InterruptedException {
    for (int round = 0; round < WARM_UP_ROUNDS; round++) {
      for (URI gateway : GATEWAYS) {
        hold(gateway, throwawayToken, "W-" + (round + 1));
        client.send(
            HttpRequest.newBuilder(gateway.resolve("/v1/events/1/seats?section=A"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());
      }
    }
  }

  private HttpResponse<String> hold(URI gateway, String token, String seatId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(gateway.resolve("/v1/holds"))
            .timeout(Duration.ofSeconds(2))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"eventId\":1,\"seatId\":\"" + seatId + "\"}"))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String tokenFor(String subject) throws IOException, InterruptedException {
    HttpResponse<String> answer =
        client.send(
            HttpRequest.newBuilder(ISSUER.resolve("/v1/tokens?subject=" + subject))
                .timeout(Duration.ofSeconds(2))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    Matcher token = ACCESS_TOKEN.matcher(answer.body());
    assertThat(token.find()).as("the issuer's answer carries an accessToken").isTrue();
    return token.group(1);
  }
}
