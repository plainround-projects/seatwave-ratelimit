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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Module 12's lab traffic against the three gateways of the running stack (127.0.0.1:21200 to
 * 21202, the issuer on 21220). It plays Module 10's balancer: request i goes to gateway i mod 3.
 * Every mode uses fans no earlier run used, so each starts with full buckets, and prints what it
 * sent and every answer it got, by status.
 *
 * <pre>
 *   paced  HOLDS PACE_MS    one fan, HOLDS holds one every PACE_MS ms (Lab 2, failure mode 1)
 *   burst  HOLDS            one fan, HOLDS holds released at the same moment (the atomic step)
 *   retry  ACCOUNTS SECONDS each account resends every refused hold at once (failure mode 4)
 *   outage SECONDS          5 holds (new fans) and 15 seat reads a second, open loop, one line
 *                           a second, while the lab script pauses and stops Redis (failure mode 2)
 *   mix    FANS BOTS        Lab 3's minute: fans hold 4 seats at 5, 8, 12 and 20 s, one in 20 is
 *                           eager and holds 6 in 2.5 s, and each bot sends 50 holds a second
 * </pre>
 */
public final class HoldTraffic {

  private static final List<URI> GATEWAYS =
      List.of(
          URI.create("http://127.0.0.1:21200"),
          URI.create("http://127.0.0.1:21201"),
          URI.create("http://127.0.0.1:21202"));
  private static final URI ISSUER = URI.create("http://127.0.0.1:21220");
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
  private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");
  // The hold policy of application.yaml: a burst of 5 and 1 token every 10 s, 0.1 per second.
  private static final double HOLD_BURST = 5;
  private static final double HOLD_REFILL_PER_SECOND = 0.1;
  private static final int TOO_MANY_REQUESTS = 429;
  private static final int UNAVAILABLE = 503;
  private static final int NO_ANSWER = 0;
  private static final int MOST_HOLDS = 10_000;
  private static final int MOST_PACE_MILLIS = 60_000;
  private static final int MOST_ACCOUNTS = 500;
  private static final int MOST_SECONDS = 300;
  private static final int MOST_FANS = 2_000;
  private static final int MOST_BOTS = 50;
  private static final int OUTAGE_HOLDS_PER_SECOND = 5;
  private static final int OUTAGE_READS_PER_SECOND = 15;
  // Lab 3's callers: the honest fan's four holds, the eager fan's six, and the bot's rate.
  private static final long[] FAN_HOLDS_MILLIS = {5_000, 8_000, 12_000, 20_000};
  private static final long[] EAGER_HOLDS_MILLIS = {0, 500, 1_000, 1_500, 2_000, 2_500};
  private static final int EAGER_EVERY = 20;
  private static final int BOT_HOLDS_PER_SECOND = 50;
  private static final long MIX_MILLIS = 60_000;
  private static final long NANOS_PER_MILLI = 1_000_000;
  private static final long NANOS_PER_SECOND = 1_000_000_000;
  private static final double NANOS_PER_SECOND_DOUBLE = 1e9;
  private static final int EXIT_USAGE = 2;
  // The issuer names fans fan-1 to fan-999999999 and booking seats A-1 to Z-99999. Each run takes
  // the 10,000 fan numbers of its second of the day (from the clock, 86,400 blocks), so fans never
  // repeat a run that came before; seats may, and a taken seat (409) still passed the limiter.
  private static final long FANS_PER_RUN = 10_000;
  private static final long RUN_FAN_BASE =
      System.currentTimeMillis() / 1_000 % Duration.ofDays(1).toSeconds() * FANS_PER_RUN;
  private static final long SEATS_PER_SECTION = 99_999;
  private static final int SECTIONS = 26;
  private static final long FIRST_OUTAGE_FAN = 1_000;
  private static final long FIRST_MIX_FAN = 5_000;
  private static final long FIRST_BOT = 100;

  private final HttpClient http;
  // Each run starts at its own place among the 2.6 million seats, so its holds rarely meet those
  // of an earlier run that are still held.
  private final AtomicLong seatNumber =
      new AtomicLong(RUN_FAN_BASE % (SEATS_PER_SECTION * SECTIONS));

  private HoldTraffic(HttpClient http) {
    this.http = http;
  }

  /**
   * Runs one mode.
   *
   * @param arguments the mode and its numbers, as the class comment lists them
   * @throws InterruptedException when the run is interrupted
   */
  public static void main(String[] arguments) throws InterruptedException {
    if (arguments.length < 2) {
      usage("expected a mode (paced, burst, retry, outage, mix) and its numbers");
    }
    try (HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()) {
      HoldTraffic traffic = new HoldTraffic(http);
      switch (arguments[0]) {
        case "paced" ->
            traffic.paced(
                whole(arguments, 1, MOST_HOLDS, "holds"),
                whole(arguments, 2, MOST_PACE_MILLIS, "pace in ms"));
        case "burst" -> traffic.burst(whole(arguments, 1, MOST_HOLDS, "holds"));
        case "retry" ->
            traffic.retry(
                whole(arguments, 1, MOST_ACCOUNTS, "accounts"),
                whole(arguments, 2, MOST_SECONDS, "seconds"));
        case "outage" -> traffic.outage(whole(arguments, 1, MOST_SECONDS, "seconds"));
        case "mix" ->
            traffic.mix(
                whole(arguments, 1, MOST_FANS, "fans"), whole(arguments, 2, MOST_BOTS, "bots"));
        default -> usage("unknown mode " + arguments[0]);
      }
    }
  }

  private void paced(int holds, int paceMillis) throws InterruptedException {
    String token = token(fan(0));
    List<Answer> answers = new ArrayList<>();
    long started = System.nanoTime();
    for (int holdIndex = 0; holdIndex < holds; holdIndex++) {
      answers.add(hold(holdIndex % GATEWAYS.size(), token));
      Thread.sleep(paceMillis);
    }
    double elapsedSeconds = (System.nanoTime() - started) / NANOS_PER_SECOND_DOUBLE;
    printHoldReport("one fan, " + holds + " holds, one every " + paceMillis + " ms", answers);
    printBound(answers, elapsedSeconds);
  }

  private void burst(int holds) throws InterruptedException {
    String token = token(fan(1));
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Answer>> pending = new ArrayList<>();
    long started;
    try (ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int holdIndex = 0; holdIndex < holds; holdIndex++) {
        int gateway = holdIndex % GATEWAYS.size();
        pending.add(
            senders.submit(() -> {
              start.await();
              return hold(gateway, token);
            }));
      }
      started = System.nanoTime();
      start.countDown();
    }
    double elapsedSeconds = (System.nanoTime() - started) / NANOS_PER_SECOND_DOUBLE;
    List<Answer> answers = collect(pending);
    printHoldReport("one fan, " + holds + " holds released together", answers);
    printBound(answers, elapsedSeconds);
  }

  private void retry(int accounts, int seconds) throws InterruptedException {
    List<String> tokens = new ArrayList<>();
    for (int account = 0; account < accounts; account++) {
      tokens.add(token(fan(FIRST_BOT + account)));
    }
    long deadline = System.nanoTime() + seconds * NANOS_PER_SECOND;
    List<Future<List<Answer>>> loops = new ArrayList<>();
    try (ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int account = 0; account < accounts; account++) {
        String token = tokens.get(account);
        int firstGateway = account;
        loops.add(
            senders.submit(() -> {
              // A loop that never waits: every answer, a refusal included, sends the next hold.
              List<Answer> sent = new ArrayList<>();
              for (int index = firstGateway; System.nanoTime() < deadline; index++) {
                sent.add(hold(index % GATEWAYS.size(), token));
              }
              return sent;
            }));
      }
    }
    List<Answer> answers = collect(loops).stream().flatMap(List::stream).toList();
    printHoldReport(accounts + " accounts, each resending at once for " + seconds + " s", answers);
    System.out.printf(
        Locale.ROOT,
        "sent %.0f holds per second in all, %.0f per account; allowed %d (%.1f per account)%n",
        answers.size() / (double) seconds,
        answers.size() / (double) seconds / accounts,
        passed(answers),
        passed(answers) / (double) accounts);
  }

  private void outage(int seconds) throws InterruptedException {
    int holds = OUTAGE_HOLDS_PER_SECOND * seconds;
    int reads = OUTAGE_READS_PER_SECOND * seconds;
    List<String> tokens = new ArrayList<>();
    for (int fan = 0; fan < holds; fan++) {
      tokens.add(token(fan(FIRST_OUTAGE_FAN + fan)));
    }
    List<Future<Answer>> holdAnswers = new ArrayList<>();
    List<Future<Answer>> readAnswers = new ArrayList<>();
    // The lab script starts its clock on this line: Redis is paused 10 s after it.
    System.out.println("sending from now, for " + seconds + " s");
    System.out.flush();
    long start = System.nanoTime();
    try (ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor()) {
      int perSecond = OUTAGE_HOLDS_PER_SECOND + OUTAGE_READS_PER_SECOND;
      for (int index = 0; index < holds + reads; index++) {
        // Open loop: request i is due at i / rate seconds, whatever happened to the ones before.
        LockSupport.parkNanos(start + index * NANOS_PER_SECOND / perSecond - System.nanoTime());
        int gateway = index % GATEWAYS.size();
        if (index % perSecond < OUTAGE_HOLDS_PER_SECOND) {
          String token = tokens.get(holdAnswers.size());
          holdAnswers.add(senders.submit(() -> hold(gateway, token)));
        } else {
          readAnswers.add(senders.submit(() -> read(gateway)));
        }
      }
    }
    printOutageTimeline(start, collect(holdAnswers), collect(readAnswers), seconds);
  }

  private void mix(int fans, int bots) throws InterruptedException {
    List<Caller> callers = new ArrayList<>();
    for (int fan = 0; fan < fans; fan++) {
      boolean isEager = fan % EAGER_EVERY == EAGER_EVERY - 1;
      callers.add(
          new Caller(
              isEager ? "eager fan" : "fan", token(fan(FIRST_MIX_FAN + fan)), times(isEager)));
    }
    for (int bot = 0; bot < bots; bot++) {
      callers.add(new Caller("bot", token(fan(FIRST_MIX_FAN + fans + bot)), botTimes()));
    }
    List<Future<Answer>> pending = new ArrayList<>();
    List<String> kinds = new ArrayList<>();
    List<long[]> schedule = new ArrayList<>();
    for (int caller = 0; caller < callers.size(); caller++) {
      for (long dueMillis : callers.get(caller).holdMillis()) {
        schedule.add(new long[] {dueMillis, caller});
      }
    }
    schedule.sort((first, second) -> Long.compare(first[0], second[0]));
    long start = System.nanoTime();
    try (ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int index = 0; index < schedule.size(); index++) {
        long[] due = schedule.get(index);
        LockSupport.parkNanos(start + due[0] * NANOS_PER_MILLI - System.nanoTime());
        Caller caller = callers.get((int) due[1]);
        int gateway = index % GATEWAYS.size();
        kinds.add(caller.kind() + " " + due[1]);
        pending.add(senders.submit(() -> hold(gateway, caller.token())));
      }
    }
    printMixReport(kinds, collect(pending));
  }

  private Answer hold(int gateway, String token) {
    long seatIndex = seatNumber.getAndIncrement();
    char section = (char) ('A' + seatIndex / SEATS_PER_SECTION % SECTIONS);
    String seat = section + "-" + (seatIndex % SEATS_PER_SECTION + 1);
    HttpRequest request =
        HttpRequest.newBuilder(GATEWAYS.get(gateway).resolve("/v1/holds"))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString("{\"eventId\":1,\"seatId\":\"" + seat + "\"}"))
            .build();
    return send(gateway, request);
  }

  private Answer read(int gateway) {
    HttpRequest request =
        HttpRequest.newBuilder(GATEWAYS.get(gateway).resolve("/v1/events/1/seats?section=A"))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
    return send(gateway, request);
  }

  private Answer send(int gateway, HttpRequest request) {
    long sent = System.nanoTime();
    try {
      HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
      // In shadow mode a would-be refusal passes, and only the RateLimit field shows it.
      boolean isWouldBeRefused =
          response.headers().firstValue("RateLimit").orElse("").contains(";r=0;t=");
      return new Answer(
          gateway, response.statusCode(), sent, System.nanoTime() - sent, isWouldBeRefused);
    } catch (IOException failed) {
      return new Answer(gateway, NO_ANSWER, sent, System.nanoTime() - sent, false);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new Answer(gateway, NO_ANSWER, sent, System.nanoTime() - sent, false);
    }
  }

  private String token(String subject) {
    HttpRequest request =
        HttpRequest.newBuilder(ISSUER.resolve("/v1/tokens?subject=" + subject))
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    try {
      String body = http.send(request, HttpResponse.BodyHandlers.ofString()).body();
      Matcher token = ACCESS_TOKEN.matcher(body);
      if (!token.find()) {
        throw new IllegalStateException("the issuer answered without a token: " + body);
      }
      return token.group(1);
    } catch (IOException unreachable) {
      throw new IllegalStateException("the issuer on " + ISSUER + " does not answer", unreachable);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while asking for a token", interrupted);
    }
  }

  private static String fan(long numberInRun) {
    return "fan-" + (RUN_FAN_BASE + numberInRun + 1);
  }

  private static long[] times(boolean isEager) {
    return isEager ? EAGER_HOLDS_MILLIS.clone() : FAN_HOLDS_MILLIS.clone();
  }

  private static long[] botTimes() {
    int holds = (int) (MIX_MILLIS / Duration.ofSeconds(1).toMillis() * BOT_HOLDS_PER_SECOND);
    long[] holdMillis = new long[holds];
    for (int index = 0; index < holds; index++) {
      holdMillis[index] = index * Duration.ofSeconds(1).toMillis() / BOT_HOLDS_PER_SECOND;
    }
    return holdMillis;
  }

  private static <T> List<T> collect(List<Future<T>> pending) throws InterruptedException {
    List<T> done = new ArrayList<>();
    for (Future<T> future : pending) {
      try {
        done.add(future.get());
      } catch (ExecutionException bug) {
        throw new IllegalStateException("a send task failed", bug.getCause());
      }
    }
    return done;
  }

  // The limiter answers only 429 (over the limit) and 503 (the hold policy without its store);
  // every other answer passed it: 201 a new hold, 409 a seat booking saw was taken, and a 500 the
  // gateway writes when its call to booking fails after the limiter allowed it.
  private static long passed(List<Answer> answers) {
    return answers.stream().filter(answer -> isPastTheLimiter(answer.status())).count();
  }

  private static boolean isPastTheLimiter(int status) {
    return status != NO_ANSWER && status != TOO_MANY_REQUESTS && status != UNAVAILABLE;
  }

  private static void printHoldReport(String title, List<Answer> answers) {
    System.out.println(title);
    System.out.println("statuses (0 = no answer): " + statuses(answers));
    StringBuilder perGateway = new StringBuilder("passed the limiter per gateway (1 / 2 / 3):");
    for (int gateway = 0; gateway < GATEWAYS.size(); gateway++) {
      int index = gateway;
      perGateway
          .append(gateway == 0 ? " " : " / ")
          .append(passed(answers.stream().filter(answer -> answer.gateway() == index).toList()));
    }
    System.out.println(perGateway);
  }

  private static void printBound(List<Answer> answers, double elapsedSeconds) {
    double bound = HOLD_BURST + HOLD_REFILL_PER_SECOND * elapsedSeconds;
    long allowed = passed(answers);
    System.out.printf(
        Locale.ROOT,
        "allowed %d of %d in %.1f s; bound 5 + 0.1 x %.1f = %.1f; bound kept: %b%n",
        allowed,
        answers.size(),
        elapsedSeconds,
        elapsedSeconds,
        bound,
        allowed <= bound);
  }

  private static void printOutageTimeline(
      long start, List<Answer> holds, List<Answer> reads, int seconds) {
    System.out.println("second  holds by status          reads by status          slowest ms");
    for (int second = 0; second < seconds; second++) {
      List<Answer> holdsNow = inSecond(holds, start, second);
      List<Answer> readsNow = inSecond(reads, start, second);
      long slowestNanos =
          Stream.concat(holdsNow.stream(), readsNow.stream())
              .mapToLong(Answer::nanos)
              .max()
              .orElse(0);
      System.out.printf(
          Locale.ROOT,
          "%6d  %-24s %-24s %10.1f%n",
          second,
          statuses(holdsNow),
          statuses(readsNow),
          slowestNanos / (double) NANOS_PER_MILLI);
    }
    System.out.println("holds in all: " + statuses(holds) + "; reads in all: " + statuses(reads));
  }

  private static List<Answer> inSecond(List<Answer> answers, long start, int second) {
    return answers.stream()
        .filter(answer -> (answer.sentNanos() - start) / NANOS_PER_SECOND == second)
        .toList();
  }

  private static void printMixReport(List<String> callers, List<Answer> answers) {
    // Per caller: holds sent, passed, refused (429) and would-be refused (shadow mode).
    Map<String, long[]> byCaller = new TreeMap<>();
    for (int index = 0; index < answers.size(); index++) {
      Answer answer = answers.get(index);
      long[] counts = byCaller.computeIfAbsent(callers.get(index), unused -> new long[4]);
      counts[0]++;
      counts[1] += isPastTheLimiter(answer.status()) ? 1 : 0;
      counts[2] += answer.status() == TOO_MANY_REQUESTS ? 1 : 0;
      counts[3] += answer.isWouldBeRefused() && answer.status() != 429 ? 1 : 0;
    }
    System.out.println(
        "kind       callers  holds sent  passed  refused  shadow-refused  callers refused");
    for (String kind : List.of("fan", "eager fan", "bot")) {
      List<long[]> rows =
          byCaller.entrySet().stream()
              .filter(entry -> entry.getKey().startsWith(kind + " "))
              .map(Map.Entry::getValue)
              .toList();
      long sent = rows.stream().mapToLong(counts -> counts[0]).sum();
      long passedCount = rows.stream().mapToLong(counts -> counts[1]).sum();
      long refused = rows.stream().mapToLong(counts -> counts[2]).sum();
      long shadow = rows.stream().mapToLong(counts -> counts[3]).sum();
      long callersRefused = rows.stream().filter(counts -> counts[2] + counts[3] > 0).count();
      System.out.printf(
          Locale.ROOT,
          "%-10s %7d  %10d  %6d  %7d  %14d  %15d%n",
          kind,
          rows.size(),
          sent,
          passedCount,
          refused,
          shadow,
          callersRefused);
    }
    System.out.println("statuses (0 = no answer): " + statuses(answers));
  }

  private static Map<Integer, Integer> statuses(List<Answer> answers) {
    Map<Integer, Integer> counts = new TreeMap<>();
    answers.forEach(answer -> counts.merge(answer.status(), 1, Integer::sum));
    return counts;
  }

  private static int whole(String[] arguments, int position, int maximum, String name) {
    if (arguments.length <= position) {
      usage("missing " + name);
    }
    try {
      int value = Integer.parseInt(arguments[position], 10);
      if (value < 1 || value > maximum) {
        usage(name + " must be from 1 to " + maximum + ", but was " + value);
      }
      return value;
    } catch (NumberFormatException notANumber) {
      usage(name + " must be a whole number, but was " + arguments[position]);
      return 0;
    }
  }

  private static void usage(String problem) {
    System.err.println("HoldTraffic: " + problem);
    System.exit(EXIT_USAGE);
  }

  /**
   * One answer.
   *
   * @param gateway which gateway, 0 to 2
   * @param status the HTTP status, 0 when no answer came
   * @param sentNanos when it was sent, on System.nanoTime
   * @param nanos from sending to the end of the answer or the failure
   * @param isWouldBeRefused whether the RateLimit field said no token was left
   */
  private record Answer(
      int gateway, int status, long sentNanos, long nanos, boolean isWouldBeRefused) {}

  /**
   * One caller of Lab 3's minute.
   *
   * @param kind fan, eager fan or bot
   * @param token its bearer token
   * @param holdMillis when it sends each hold, from the start
   */
  private record Caller(String kind, String token, long[] holdMillis) {}
}
