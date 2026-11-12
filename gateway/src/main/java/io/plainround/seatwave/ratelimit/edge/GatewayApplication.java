package io.plainround.seatwave.ratelimit.edge;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * Starts SeatWave's edge gateway: the routes of {@code application.yaml}, the token check of {@link
 * SecurityConfiguration}, and the identity header of {@link IdentityHeaderFilter}.
 */
// No bean method calls another, so Spring needs no subclass of this class and it can be final.
@SpringBootApplication(proxyBeanMethods = false)
@ConfigurationPropertiesScan
public final class GatewayApplication {

  /**
   * The one clock of the gateway, in UTC; the token rules read the time from it, so a test can fix
   * it.
   *
   * @return the system clock in UTC
   */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * Runs the gateway until it is stopped.
   *
   * @param arguments Spring Boot's command-line arguments
   */
  public static void main(String[] arguments) {
    SpringApplication.run(GatewayApplication.class, arguments);
  }
}
