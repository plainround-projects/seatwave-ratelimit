package io.plainround.seatwave.ratelimit.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

class CatalogPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(Settings.class)
          .withPropertyValues(
              "seatwave.catalog.event-id=1",
              "seatwave.catalog.event-name=Arena night",
              "seatwave.catalog.venue=Arena",
              "seatwave.catalog.starts-at=2026-12-04T19:00:00Z",
              "seatwave.catalog.sections=20",
              "seatwave.catalog.seats-per-section=1000",
              "seatwave.catalog.default-page-size=100",
              "seatwave.catalog.max-page-size=500");

  @Test
  void acceptsModuleOnesArena() {
    runner.run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void refusesADefaultPageLargerThanTheMaximum() {
    runner
        .withPropertyValues("seatwave.catalog.default-page-size=600")
        .run(context ->
            assertThat(context.getStartupFailure())
                .rootCause()
                .hasMessageContaining("defaultPageSize must not be larger than maxPageSize"));
  }

  @Test
  void reportsEveryBrokenRuleTogetherWithoutARepeatedCrossKeyLine() {
    runner
        .withPropertyValues("seatwave.catalog.sections=27", "seatwave.catalog.max-page-size=0")
        .run(context -> {
          String message = rootMessage(context.getStartupFailure());
          assertThat(message).contains("sections", "maxPageSize");
          assertThat(message).doesNotContain("defaultPageSize must not be larger");
        });
  }

  private static String rootMessage(Throwable failure) {
    Throwable cause = failure;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause.getMessage();
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(CatalogProperties.class)
  static class Settings {}
}
