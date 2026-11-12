package io.plainround.seatwave.ratelimit.labtools;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Times the mobile seat screen on this machine's loopback interface, the app's five sequential
 * calls against the backend-for-frontend's one fanned-out call, first with a healthy booking
 * service and then with one that takes 2 s to answer. It prints what it measured, then adds the 4G
 * round trips of Section 7 by arithmetic, which it labels as a model. Section 8 runs it with {@code
 * javac} and {@code java}; {@code ./gradlew -q :labtools:fanOut} runs the same class.
 */
public final class FanOutDemo {

  // Stand-in service times: about what Module 1 measured for building one seat-map page.
  private static final Duration CATALOG_TIME = Duration.ofMillis(5);
  private static final Duration BOOKING_TIME = Duration.ofMillis(5);
  private static final Duration SLOW_BOOKING_TIME = Duration.ofSeconds(2);
  // The BFF's budgets (mobile-bff/application.yaml) and the app's own patience.
  private static final Duration CATALOG_TIMEOUT = Duration.ofMillis(150);
  private static final Duration BOOKING_TIMEOUT = Duration.ofMillis(100);
  private static final Duration APP_TIMEOUT = Duration.ofSeconds(5);
  // Section 7: 80 ms per round trip on 4G, a 10 ms gateway budget.
  private static final int MOBILE_ROUND_TRIP_MILLIS = 80;
  private static final int GATEWAY_BUDGET_MILLIS = 10;
  private static final int SEQUENTIAL_CALLS = 5;
  private static final int WARM_UP_SCREENS = 20;
  private static final int SCREENS = 50;
  private static final int SLOW_SCREENS = 5;
  private static final double P50 = 0.50;
  private static final double P99 = 0.99;
  private static final double NANOS_PER_MILLI = 1_000_000.0;

  private FanOutDemo() {}

  /**
   * Runs both scenarios and prints one row per way of fetching.
   *
   * @param arguments none
   * @throws IOException when the loopback stand-ins cannot start
   * @throws InterruptedException when the run is interrupted
   */
  public static void main(String[] arguments) throws IOException, InterruptedException {
    try (StubServices services = new StubServices(CATALOG_TIME, BOOKING_TIME);
        HttpClient http = HttpClient.newBuilder().connectTimeout(APP_TIMEOUT).build()) {
      ScreenFetcher fetcher =
          new ScreenFetcher(http, services.base(), CATALOG_TIMEOUT, BOOKING_TIMEOUT, APP_TIMEOUT);
      for (int screen = 0; screen < WARM_UP_SCREENS; screen++) {
        fetcher.sequential();
        fetcher.fannedOut();
      }
      System.out.println(
          "scenario        way                               screens  p50 ms  p99 ms  max ms  partial  body bytes to phone");
      List<ScreenResult> sequential = run(fetcher, true, SCREENS);
      List<ScreenResult> fannedOut = run(fetcher, false, SCREENS);
      print("booking 5 ms", "app: five sequential calls", sequential);
      print("booking 5 ms", "BFF: one call, fanned out", fannedOut);
      services.slowBookingTo(SLOW_BOOKING_TIME);
      print("booking 2 s", "app: five sequential calls", run(fetcher, true, SLOW_SCREENS));
      print("booking 2 s", "BFF: one call, fanned out", run(fetcher, false, SLOW_SCREENS));
      double sequentialMillis = millis(percentile(sequential, P50));
      double fannedOutMillis = millis(percentile(fannedOut, P50));
      System.out.printf(
          Locale.ROOT,
          "model, not measured: on 4G at %d ms per round trip, the app's screen is about %.1f + %d x %d = %.1f ms;"
              + " the BFF's is about %.1f + %d + 1 x %d = %.1f ms%n",
          MOBILE_ROUND_TRIP_MILLIS,
          sequentialMillis,
          SEQUENTIAL_CALLS,
          MOBILE_ROUND_TRIP_MILLIS,
          sequentialMillis + SEQUENTIAL_CALLS * MOBILE_ROUND_TRIP_MILLIS,
          fannedOutMillis,
          GATEWAY_BUDGET_MILLIS,
          MOBILE_ROUND_TRIP_MILLIS,
          fannedOutMillis + GATEWAY_BUDGET_MILLIS + MOBILE_ROUND_TRIP_MILLIS);
    }
  }

  private static List<ScreenResult> run(ScreenFetcher fetcher, boolean isSequential, int screens)
      throws IOException, InterruptedException {
    List<ScreenResult> results = new ArrayList<>();
    for (int screen = 0; screen < screens; screen++) {
      results.add(isSequential ? fetcher.sequential() : fetcher.fannedOut());
    }
    return results;
  }

  private static void print(String scenario, String way, List<ScreenResult> results) {
    long partial = results.stream().filter(ScreenResult::isPartial).count();
    // Every screen of a row should carry the same bytes; a row whose screens differ says so.
    int fewestBytes = results.stream().mapToInt(ScreenResult::bytesToPhone).min().orElseThrow();
    int mostBytes = results.stream().mapToInt(ScreenResult::bytesToPhone).max().orElseThrow();
    String bytes =
        fewestBytes == mostBytes ? String.valueOf(fewestBytes) : fewestBytes + " to " + mostBytes;
    System.out.printf(
        Locale.ROOT,
        "%-15s %-33s %7d %7.1f %7.1f %7.1f %8d %20s%n",
        scenario,
        way,
        results.size(),
        millis(percentile(results, P50)),
        millis(percentile(results, P99)),
        millis(percentile(results, 1.0)),
        partial,
        bytes);
  }

  // Nearest rank: the smallest value with at least that share of results at or below it.
  private static Duration percentile(List<ScreenResult> results, double share) {
    List<Duration> sorted = results.stream().map(ScreenResult::elapsed).sorted().toList();
    int rank = (int) Math.ceil(share * sorted.size());
    return sorted.get(Math.max(rank, 1) - 1);
  }

  private static double millis(Duration elapsed) {
    return elapsed.toNanos() / NANOS_PER_MILLI;
  }
}
