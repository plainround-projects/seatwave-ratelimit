package io.plainround.seatwave.ratelimit.booking.shedding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/** Every broken shedding setting is a start-up problem, reported together with the others. */
class SheddingPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(Settings.class)
          .withPropertyValues(
              "seatwave.shedding.max-in-flight=64", "seatwave.shedding.retry-after=1s");

  @Test
  void acceptsModuleFoursLimitOfSixtyFour() {
    runner.run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void refusesARetryAfterBelowOneSecond() {
    runner
        .withPropertyValues("seatwave.shedding.retry-after=500ms")
        .run(context ->
            assertThat(problems(context.getStartupFailure()))
                .contains("retryAfter must be at least 1s")
                .doesNotContain("maxInFlight"));
  }

  @Test
  void reportsBothBrokenRulesTogether() {
    runner
        .withPropertyValues("seatwave.shedding.max-in-flight=0", "seatwave.shedding.retry-after=0s")
        .run(context ->
            assertThat(problems(context.getStartupFailure()))
                .contains("maxInFlight", "retryAfter must be at least 1s"));
  }

  @Test
  void refusesALimitAboveOneHundredThousand() {
    runner
        .withPropertyValues("seatwave.shedding.max-in-flight=100001")
        .run(context -> assertThat(problems(context.getStartupFailure())).contains("maxInFlight"));
  }

  private static String problems(Throwable failure) {
    StringBuilder messages = new StringBuilder();
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      messages.append(cause.getMessage()).append('\n');
    }
    return messages.toString();
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(SheddingProperties.class)
  static class Settings {}
}
