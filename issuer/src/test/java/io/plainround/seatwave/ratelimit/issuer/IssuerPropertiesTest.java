package io.plainround.seatwave.ratelimit.issuer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/** The token lifetime and the names the issuer writes are checked at start-up. */
class IssuerPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(Settings.class)
          .withPropertyValues(
              "seatwave.issuer.issuer=http://issuer:8080",
              "seatwave.issuer.audience=seatwave-api",
              "seatwave.issuer.token-lifetime=15m",
              "seatwave.issuer.key-file=build/keys/issuer-keys.json");

  @Test
  void acceptsTheLabsFifteenMinuteTokens() {
    runner.run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void acceptsATokenLifetimeOfExactlyOneHour() {
    runner
        .withPropertyValues("seatwave.issuer.token-lifetime=1h")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void refusesATokenLifetimeLongerThanOneHour() {
    runner
        .withPropertyValues("seatwave.issuer.token-lifetime=61m")
        .run(context ->
            assertThat(rootMessage(context.getStartupFailure()))
                .contains("tokenLifetime must be more than 0 and at most 1h"));
  }

  @Test
  void refusesATokenLifetimeOfZero() {
    runner
        .withPropertyValues("seatwave.issuer.token-lifetime=0s")
        .run(context ->
            assertThat(rootMessage(context.getStartupFailure()))
                .contains("tokenLifetime must be more than 0 and at most 1h"));
  }

  @Test
  void refusesAnAudienceWithCharactersATokenShouldNotCarry() {
    runner
        .withPropertyValues("seatwave.issuer.audience=Seatwave API")
        .run(context -> assertThat(rootMessage(context.getStartupFailure())).contains("audience"));
  }

  private static String rootMessage(Throwable failure) {
    Throwable cause = failure;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause.getMessage();
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(IssuerProperties.class)
  static class Settings {}
}
