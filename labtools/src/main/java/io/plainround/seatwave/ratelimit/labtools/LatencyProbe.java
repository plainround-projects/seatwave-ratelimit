package io.plainround.seatwave.ratelimit.labtools;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

/**
 * Lab 1's open-loop probe: sends exactly {@code rate × seconds} GET requests to one URL on a fixed
 * schedule (request i is due at i ÷ rate seconds, so a late answer never delays the next request),
 * records one outcome per planned request, and prints the statuses and the latency percentiles of
 * the answered ones. Run it once against a service and once against the gateway, at the same rate,
 * and subtract. Usage: {@code LatencyProbe <url> <requests per second> <seconds> [token]}. A fourth
 * argument of the form {@code subject=fan-42} sends {@code X-SeatWave-Subject: fan-42} instead of a
 * token: that is what the gateway sends inward, so the same request can be timed directly against
 * the booking service, which trusts that header. A fourth argument {@code address=random} sends
 * {@code X-Forwarded-For} with a new documentation address (198.18.0.0/15, RFC 2544) on every
 * request: Module 12's gateway trusts that header from the lab network, so each request is its own
 * address key and a seat-read limit keyed by address never refuses the probe.
 */
public final class LatencyProbe {

  private static final int MAX_RATE = 5_000;
  private static final int MAX_SECONDS = 300;
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
  private static final long NANOS_PER_SECOND = 1_000_000_000L;
  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static final int EXIT_USAGE = 2;
  private static final int ARGUMENTS_WITHOUT_TOKEN = 3;
  private static final double P50 = 0.50;
  private static final double P99 = 0.99;
  // The outcome recorded for a request that got no answer: status 0 is not an HTTP status.
  private static final int NO_ANSWER = 0;
  private static final String SUBJECT_PREFIX = "subject=";
  private static final String RANDOM_ADDRESS = "address=random";
  // 198.18.0.0/15: 131,072 addresses reserved for benchmarks, none of them a real client's.
  private static final int BENCHMARK_ADDRESSES = 1 << 17;
  private static final int OCTET = 256;
  private static final int FIRST_BENCHMARK_OCTET = 18;

  private LatencyProbe() {}

  /**
   * Runs one probe.
   *
   * @param arguments the URL, the rate, the duration and an optional bearer token
   * @throws InterruptedException when the run is interrupted
   */
  public static void main(String[] arguments) throws InterruptedException {
    if (arguments.length < ARGUMENTS_WITHOUT_TOKEN
        || arguments.length > ARGUMENTS_WITHOUT_TOKEN + 1) {
      usage("expected <url> <requests per second> <seconds> [token]");
    }
    URI target = URI.create(arguments[0]);
    int rate = whole(arguments[1], MAX_RATE, "requests per second");
    int seconds = whole(arguments[2], MAX_SECONDS, "seconds");
    String token =
        arguments.length > ARGUMENTS_WITHOUT_TOKEN ? arguments[ARGUMENTS_WITHOUT_TOKEN] : "";
    int planned = Math.multiplyExact(rate, seconds);
    List<Future<Outcome>> outcomes = new ArrayList<>(planned);
    try (HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor()) {
      long start = System.nanoTime();
      for (int index = 0; index < planned; index++) {
        // Each due time comes from the index, never from a rounded interval, so no request is lost
        // to rounding and request i is due exactly i / rate seconds after the start.
        long due = start + Math.multiplyExact((long) index, NANOS_PER_SECOND) / rate;
        LockSupport.parkNanos(due - System.nanoTime());
        outcomes.add(senders.submit(() -> send(http, target, token)));
      }
    }
    report(planned, outcomes);
  }

  private static Outcome send(HttpClient http, URI target, String token) {
    HttpRequest.Builder request = HttpRequest.newBuilder(target).timeout(REQUEST_TIMEOUT);
    if (token.equals(RANDOM_ADDRESS)) {
      int host = ThreadLocalRandom.current().nextInt(BENCHMARK_ADDRESSES);
      request.header(
          "X-Forwarded-For",
          String.format(
              Locale.ROOT,
              "198.%d.%d.%d",
              FIRST_BENCHMARK_OCTET + host / (OCTET * OCTET),
              host / OCTET % OCTET,
              host % OCTET));
    } else if (token.startsWith(SUBJECT_PREFIX)) {
      request.header("X-SeatWave-Subject", token.substring(SUBJECT_PREFIX.length()));
    } else if (!token.isEmpty()) {
      request.header("Authorization", "Bearer " + token);
    }
    long sent = System.nanoTime();
    try {
      HttpResponse<Void> response =
          http.send(request.build(), HttpResponse.BodyHandlers.discarding());
      return new Outcome(response.statusCode(), System.nanoTime() - sent);
    } catch (IOException failed) {
      return new Outcome(NO_ANSWER, System.nanoTime() - sent);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new Outcome(NO_ANSWER, System.nanoTime() - sent);
    }
  }

  private static void report(int planned, List<Future<Outcome>> futures)
      throws InterruptedException {
    Map<Integer, Integer> statuses = new TreeMap<>();
    Map<Integer, List<Long>> nanosByStatus = new TreeMap<>();
    List<Long> answeredNanos = new ArrayList<>();
    for (Future<Outcome> future : futures) {
      Outcome outcome;
      try {
        outcome = future.get();
      } catch (ExecutionException bug) {
        throw new IllegalStateException("a send task failed", bug.getCause());
      }
      statuses.merge(outcome.status(), 1, Integer::sum);
      if (outcome.status() != NO_ANSWER) {
        answeredNanos.add(outcome.nanos());
        nanosByStatus
            .computeIfAbsent(outcome.status(), unused -> new ArrayList<>())
            .add(outcome.nanos());
      }
    }
    answeredNanos.sort(null);
    int noAnswer = statuses.getOrDefault(NO_ANSWER, 0);
    System.out.printf(
        Locale.ROOT,
        "planned %d, recorded %d, answered %d, no answer %d%n",
        planned,
        futures.size(),
        answeredNanos.size(),
        noAnswer);
    System.out.println("statuses (0 = no answer): " + statuses);
    if (answeredNanos.isEmpty()) {
      System.out.println("latency: no request was answered");
      return;
    }
    System.out.printf(
        Locale.ROOT,
        "latency of answered requests: p50 %.2f ms, p99 %.2f ms, max %.2f ms%n",
        answeredNanos.get(rank(P50, answeredNanos.size())) / NANOS_PER_MILLI,
        answeredNanos.get(rank(P99, answeredNanos.size())) / NANOS_PER_MILLI,
        answeredNanos.getLast() / NANOS_PER_MILLI);
    if (nanosByStatus.size() > 1) {
      // A refusal answered at once and a request served slowly are different stories; one set of
      // percentiles over both would hide each.
      nanosByStatus.forEach(LatencyProbe::printStatusLatency);
    }
  }

  private static void printStatusLatency(int status, List<Long> nanos) {
    nanos.sort(null);
    System.out.printf(
        Locale.ROOT,
        "  status %d: %d answers, p50 %.2f ms, p99 %.2f ms, max %.2f ms%n",
        status,
        nanos.size(),
        nanos.get(rank(P50, nanos.size())) / NANOS_PER_MILLI,
        nanos.get(rank(P99, nanos.size())) / NANOS_PER_MILLI,
        nanos.getLast() / NANOS_PER_MILLI);
  }

  // Nearest rank, as an index into the sorted list.
  private static int rank(double share, int size) {
    return Math.max((int) Math.ceil(share * size), 1) - 1;
  }

  private static int whole(String text, int maximum, String name) {
    try {
      int value = Integer.parseInt(text, 10);
      if (value < 1 || value > maximum) {
        usage(name + " must be from 1 to " + maximum + ", but was " + value);
      }
      return value;
    } catch (NumberFormatException notANumber) {
      usage(name + " must be a whole number, but was " + text);
      return 0;
    }
  }

  private static void usage(String problem) {
    System.err.println("LatencyProbe: " + problem);
    System.exit(EXIT_USAGE);
  }

  /**
   * What happened to one planned request.
   *
   * @param status the HTTP status, or 0 when no answer came
   * @param nanos from sending to the end of the answer, or to the failure
   */
  private record Outcome(int status, long nanos) {}
}
