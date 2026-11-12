package io.plainround.seatwave.ratelimit.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Starts the mobile backend-for-frontend, reachable only through the gateway. */
// No bean method calls another, so Spring needs no subclass of this class and it can be final.
@SpringBootApplication(proxyBeanMethods = false)
@ConfigurationPropertiesScan
public final class MobileBffApplication {

  /**
   * Runs the service until it is stopped.
   *
   * @param arguments Spring Boot's command-line arguments
   */
  public static void main(String[] arguments) {
    SpringApplication.run(MobileBffApplication.class, arguments);
  }
}
