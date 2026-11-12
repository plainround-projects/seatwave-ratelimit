package io.plainround.seatwave.ratelimit.issuer;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/** Starts the lab's token issuer stand-in. */
// No bean method calls another, so Spring needs no subclass of this class and it can be final.
@SpringBootApplication(proxyBeanMethods = false)
@ConfigurationPropertiesScan
public final class IssuerApplication {

  /**
   * The issuer's clock, in UTC.
   *
   * @return the system clock in UTC
   */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * Runs the issuer until it is stopped.
   *
   * @param arguments Spring Boot's command-line arguments
   */
  public static void main(String[] arguments) {
    SpringApplication.run(IssuerApplication.class, arguments);
  }
}
