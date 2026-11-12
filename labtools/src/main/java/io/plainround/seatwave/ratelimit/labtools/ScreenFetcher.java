package io.plainround.seatwave.ratelimit.labtools;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches the mobile seat screen the two ways the module compares: the app's five sequential calls,
 * and the backend-for-frontend's one call that fans out to the same five in parallel, with per-call
 * timeouts and a partial answer when the optional parts are late. Thread-safe: the JDK client is,
 * and nothing else is shared.
 */
final class ScreenFetcher {

  // The one status the stand-ins answer with; anything else fails the call.
  private static final int OK = 200;
  private static final Pattern SEAT_ID = Pattern.compile("\"seatId\":\"([A-Z]-[0-9]+)\"");

  private final HttpClient http;
  private final URI base;
  private final Duration catalogTimeout;
  private final Duration bookingTimeout;
  private final Duration appTimeout;

  ScreenFetcher(
      HttpClient http,
      URI base,
      Duration catalogTimeout,
      Duration bookingTimeout,
      Duration appTimeout) {
    this.http = http;
    this.base = base;
    this.catalogTimeout = catalogTimeout;
    this.bookingTimeout = bookingTimeout;
    this.appTimeout = appTimeout;
  }

  /** The app today: event, seats page 1, seats page 2, holds, bookings, one after the other. */
  ScreenResult sequential() throws IOException, InterruptedException {
    long start = System.nanoTime();
    String event = get("/v1/events/1", appTimeout);
    String firstPage = get("/v1/events/1/seats?section=A&limit=500", appTimeout);
    String cursor = CompactJson.field(firstPage, "nextCursor").orElseThrow();
    String secondPage = get("/v1/events/1/seats?section=A&limit=500&cursor=" + cursor, appTimeout);
    String holds = get("/v1/holds?eventId=1", appTimeout);
    String bookings = get("/v1/bookings?eventId=1", appTimeout);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
    int bytes = utf8(event) + utf8(firstPage) + utf8(secondPage) + utf8(holds) + utf8(bookings);
    return new ScreenResult(elapsed, bytes, false);
  }

  /** The backend-for-frontend: the same five calls, four branches in parallel, one answer. */
  ScreenResult fannedOut() throws InterruptedException {
    long start = System.nanoTime();
    try (ExecutorService parts = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<String> event = parts.submit(() -> get("/v1/events/1", catalogTimeout));
      // The two pages depend on each other, so they stay in order inside one branch.
      Future<List<String>> seats = parts.submit(this::seatIds);
      Future<String> holds = parts.submit(() -> get("/v1/holds?eventId=1", bookingTimeout));
      Future<String> bookings = parts.submit(() -> get("/v1/bookings?eventId=1", bookingTimeout));
      String eventName = CompactJson.field(essential(event), "name").orElseThrow();
      List<String> seatIds = essential(seats);
      List<String> missing = new ArrayList<>();
      List<String> held = optional(holds, "holds", missing);
      List<String> booked = optional(bookings, "bookings", missing);
      String screen =
          "{\"eventName\":\""
              + eventName
              + "\",\"section\":\"A\",\"seats\":"
              + list(seatIds)
              + ",\"heldByYou\":"
              + list(held)
              + ",\"bookedByYou\":"
              + list(booked)
              + ",\"partial\":"
              + !missing.isEmpty()
              + ",\"missing\":"
              + list(missing)
              + "}";
      Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
      return new ScreenResult(elapsed, utf8(screen), !missing.isEmpty());
    }
  }

  private List<String> seatIds() throws IOException, InterruptedException {
    String firstPage = get("/v1/events/1/seats?section=A&limit=500", catalogTimeout);
    String cursor = CompactJson.field(firstPage, "nextCursor").orElseThrow();
    String secondPage =
        get("/v1/events/1/seats?section=A&limit=500&cursor=" + cursor, catalogTimeout);
    List<String> seatIds = new ArrayList<>(ids(firstPage));
    seatIds.addAll(ids(secondPage));
    return seatIds;
  }

  private String get(String path, Duration timeout) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(base.resolve(path)).timeout(timeout).build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != OK) {
      throw new IOException("status " + response.statusCode() + " from " + path);
    }
    return response.body();
  }

  private static <T> T essential(Future<T> part) throws InterruptedException {
    try {
      return part.get();
    } catch (ExecutionException failed) {
      throw new IllegalStateException("the seat map is unavailable: " + failed.getCause(), failed);
    }
  }

  private static List<String> optional(Future<String> part, String name, List<String> missing)
      throws InterruptedException {
    try {
      return ids(part.get());
    } catch (ExecutionException late) {
      missing.add(name);
      return List.of();
    }
  }

  private static List<String> ids(String json) {
    List<String> found = new ArrayList<>();
    Matcher match = SEAT_ID.matcher(json);
    while (match.find()) {
      found.add(match.group(1));
    }
    return found;
  }

  private static String list(List<String> items) {
    return items.stream().map(item -> "\"" + item + "\"").toList().toString().replace(", ", ",");
  }

  private static int utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8).length;
  }
}
