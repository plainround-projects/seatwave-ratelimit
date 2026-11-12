package io.plainround.seatwave.ratelimit.booking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/** Every broken booking setting is a start-up problem, reported together with the others. */
class BookingPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(Settings.class)
          .withPropertyValues(
              "seatwave.booking.hold-duration=300s",
              "seatwave.booking.read-delay=0s",
              "seatwave.booking.read-workers=10000");

  @Test
  void acceptsModuleOnesHoldWithoutAnInjectedDelay() {
    runner.run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void acceptsTheSlowBackendLabsDelayOfTwoSeconds() {
    runner
        .withPropertyValues("seatwave.booking.read-delay=2s")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void refusesAHoldThatLastsNoTime() {
    runner
        .withPropertyValues("seatwave.booking.hold-duration=0s")
        .run(context ->
            assertThat(rootMessage(context.getStartupFailure()))
                .contains("holdDuration must be positive")
                .doesNotContain("readDelay"));
  }

  @Test
  void refusesAnInjectedDelayLongerThanThirtySeconds() {
    runner
        .withPropertyValues("seatwave.booking.read-delay=31s")
        .run(context ->
            assertThat(rootMessage(context.getStartupFailure()))
                .contains("readDelay must be from 0 to 30s")
                .doesNotContain("holdDuration"));
  }

  @Test
  void reportsBothBrokenRulesTogether() {
    runner
        .withPropertyValues("seatwave.booking.hold-duration=-1s", "seatwave.booking.read-delay=-1s")
        .run(context ->
            assertThat(rootMessage(context.getStartupFailure()))
                .contains("holdDuration must be positive", "readDelay must be from 0 to 30s"));
  }

  @Test
  void reportsAMissingSettingByName() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
        .withUserConfiguration(Settings.class)
        .withPropertyValues("seatwave.booking.read-delay=0s", "seatwave.booking.read-workers=8")
        .run(context ->
            assertThat(rootMessage(context.getStartupFailure())).contains("holdDuration"));
  }

  @Test
  void refusesAReadLineWithNoWorker() {
    runner
        .withPropertyValues("seatwave.booking.read-workers=0")
        .run(context ->
            assertThat(rootMessage(context.getStartupFailure()))
                .contains("readWorkers")
                .doesNotContain("readDelay"));
  }

  private static String rootMessage(Throwable failure) {
    Throwable cause = failure;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause.getMessage();
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(BookingProperties.class)
  static class Settings {}
}
