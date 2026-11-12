package io.plainround.seatwave.ratelimit.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogHttpTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

  @LocalServerPort private int port;

  @AfterAll
  static void closeClient() {
    CLIENT.close();
  }

  @Test
  void describesTheArenaEventWithItsTwentySections() throws Exception {
    HttpResponse<String> response = get("/v1/events/1");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("\"eventId\":1", "\"startsAt\":\"2026-12-04T19:00:00Z\"", "\"A\"", "\"T\"")
        .doesNotContain("\"U\"");
  }

  @Test
  void pagesASectionOfOneThousandSeatsInTwoPagesOfFiveHundred() throws Exception {
    HttpResponse<String> first = get("/v1/events/1/seats?section=A&limit=500");
    HttpResponse<String> second = get("/v1/events/1/seats?section=A&limit=500&cursor=501");

    assertThat(first.body()).contains("\"seatId\":\"A-500\"", "\"nextCursor\":\"501\"");
    assertThat(first.body()).doesNotContain("\"seatId\":\"A-501\"");
    assertThat(second.body()).contains("\"seatId\":\"A-501\"", "\"seatId\":\"A-1000\"");
    assertThat(second.body()).doesNotContain("nextCursor");
  }

  @Test
  void givesOneHundredSeatsWhenNoLimitIsNamed() throws Exception {
    HttpResponse<String> response = get("/v1/events/1/seats?section=B");

    assertThat(response.body()).contains("\"seatId\":\"B-100\"", "\"nextCursor\":\"101\"");
    assertThat(response.body()).doesNotContain("\"seatId\":\"B-101\"");
  }

  @Test
  void refusesAPageLargerThanFiveHundredWithAProblem() throws Exception {
    HttpResponse<String> response = get("/v1/events/1/seats?section=A&limit=501");

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    assertThat(response.body()).contains("limit must be from 1 to 500");
  }

  @Test
  void refusesACursorPastTheLastSeat() throws Exception {
    HttpResponse<String> response = get("/v1/events/1/seats?section=A&cursor=1001");

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("cursor is not one this API gave");
  }

  @Test
  void refusesAMalformedCursor() throws Exception {
    HttpResponse<String> response = get("/v1/events/1/seats?section=A&cursor=x1");

    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void refusesALowerCaseSection() throws Exception {
    HttpResponse<String> response = get("/v1/events/1/seats?section=a");

    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void answersNotFoundForASectionTheArenaDoesNotHave() throws Exception {
    HttpResponse<String> response = get("/v1/events/1/seats?section=U");

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  void answersNotFoundForAnUnknownEvent() throws Exception {
    assertThat(get("/v1/events/2").statusCode()).isEqualTo(404);
    assertThat(get("/v1/events/2/seats?section=A").statusCode()).isEqualTo(404);
  }

  @Test
  void refusesAMalformedEventId() throws Exception {
    assertThat(get("/v1/events/01").statusCode()).isEqualTo(400);
    assertThat(get("/v1/events/x/seats?section=A").statusCode()).isEqualTo(400);
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(TIMEOUT)
            .build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
