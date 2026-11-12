package io.plainround.seatwave.ratelimit.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The shedding lab's stand-in for a database pool: with one read worker and a 200 ms read delay,
 * three reads sent together are served one after the other, so the last one ends no sooner than 600
 * ms after the first was sent. Without the line they would all end after about 200 ms.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"seatwave.booking.read-delay=200ms", "seatwave.booking.read-workers=1"})
class ReadLineTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private static final int READS = 3;
  private static final Duration ONE_AFTER_THE_OTHER = Duration.ofMillis(600);

  @LocalServerPort private int port;

  @AfterAll
  static void closeClient() {
    CLIENT.close();
  }

  @Test
  void servesDelayedReadsOneAtATimeWithOneWorker() throws Exception {
    HttpRequest read =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/holds?eventId=1"))
            .timeout(TIMEOUT)
            .header("X-SeatWave-Subject", "fan-42")
            .build();
    List<Future<HttpResponse<Void>>> answers = new ArrayList<>();
    long started = System.nanoTime();

    try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int reader = 0; reader < READS; reader++) {
        answers.add(
            readers.submit(() -> CLIENT.send(read, HttpResponse.BodyHandlers.discarding())));
      }
    }

    assertThat(Duration.ofNanos(System.nanoTime() - started))
        .isGreaterThanOrEqualTo(ONE_AFTER_THE_OTHER);
    for (Future<HttpResponse<Void>> answer : answers) {
      assertThat(answer.get().statusCode()).isEqualTo(200);
    }
  }
}
