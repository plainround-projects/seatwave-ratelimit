package io.plainround.seatwave.ratelimit.booking;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/** Starts the booking service: holds and bookings, reachable only through the gateway. */
// No bean method calls another, so Spring needs no subclass of this class and it can be final.
@SpringBootApplication(proxyBeanMethods = false)
@ConfigurationPropertiesScan
public final class BookingApplication {

  /**
   * The one clock of the service, in UTC, so tests can replace it with a fixed one.
   *
   * @return the system clock in UTC
   */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * Runs the service until it is stopped.
   *
   * @param arguments Spring Boot's command-line arguments
   */
  public static void main(String[] arguments) {
    SpringApplication.run(BookingApplication.class, arguments);
  }
}
