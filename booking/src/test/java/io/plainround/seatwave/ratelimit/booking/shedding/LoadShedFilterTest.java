package io.plainround.seatwave.ratelimit.booking.shedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Booking's shedder: one request beyond the in-flight limit is refused at once with 503 and
 * Retry-After, and every admitted request gives its place back, on the error path too.
 */
class LoadShedFilterTest {

  private static final SheddingProperties ONE_AT_A_TIME =
      new SheddingProperties(1, Duration.ofSeconds(1));
  private static final long WAIT_SECONDS = 5;

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final LoadShedFilter shedder = new LoadShedFilter(ONE_AT_A_TIME, meters);
  // The first request waits on its own thread while the test sends the second one.
  private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();

  @AfterEach
  void stopTheHandlers() {
    handlers.close();
  }

  @Test
  void shedsARequestBeyondTheLimitAtOnceWithRetryAfter() throws Exception {
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<MockHttpServletResponse> first =
        handlers.submit(() -> run(waitingChain(inside, release)));
    assertThat(inside.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

    MockHttpServletResponse second = run(new MockFilterChain());
    release.countDown();

    assertThat(second.getStatus()).isEqualTo(503);
    assertThat(second.getHeader("Retry-After")).isEqualTo("1");
    assertThat(second.getContentAsString()).contains("temporary-reduced-capacity");
    assertThat(meters.counter("seatwave.booking.shed", "path", "holds").count()).isEqualTo(1);
    assertThat(first.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
  }

  @Test
  void admitsTheNextRequestOnceTheFirstHasLeft() {
    run(new MockFilterChain());

    MockHttpServletResponse next = run(new MockFilterChain());

    assertThat(next.getStatus()).isEqualTo(200);
    assertThat(meters.get("seatwave.booking.in.flight").gauge().value()).isZero();
  }

  @Test
  void givesThePlaceBackWhenTheHandlerFails() {
    FilterChain failing =
        (request, response) -> {
          throw new ServletException("the handler failed");
        };
    assertThatThrownBy(() -> shedder.doFilter(hold(), new MockHttpServletResponse(), failing))
        .isInstanceOf(ServletException.class);

    assertThat(run(new MockFilterChain()).getStatus()).isEqualTo(200);
  }

  @Test
  void countsAShedRequestOutsideHoldsUnderOther() throws Exception {
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<MockHttpServletResponse> first =
        handlers.submit(() -> run(waitingChain(inside, release)));
    assertThat(inside.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

    MockHttpServletResponse response = new MockHttpServletResponse();
    shedder.doFilter(
        new MockHttpServletRequest("GET", "/v1/bookings"), response, new MockFilterChain());
    release.countDown();
    first.get(WAIT_SECONDS, TimeUnit.SECONDS);

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(meters.counter("seatwave.booking.shed", "path", "other").count()).isEqualTo(1);
  }

  private MockHttpServletResponse run(FilterChain chain) {
    MockHttpServletResponse response = new MockHttpServletResponse();
    try {
      shedder.doFilter(hold(), response, chain);
    } catch (Exception unexpected) {
      throw new IllegalStateException("the shedder failed", unexpected);
    }
    return response;
  }

  private static MockHttpServletRequest hold() {
    return new MockHttpServletRequest("POST", "/v1/holds");
  }

  // A handler that says it is inside, then waits until the test lets it finish.
  private static FilterChain waitingChain(CountDownLatch inside, CountDownLatch release) {
    return (request, response) -> {
      inside.countDown();
      try {
        if (!release.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
          throw new ServletException("the test never released the handler");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new ServletException("interrupted", interrupted);
      }
    };
  }
}
