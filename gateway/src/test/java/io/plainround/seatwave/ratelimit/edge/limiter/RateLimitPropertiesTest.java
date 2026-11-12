package io.plainround.seatwave.ratelimit.edge.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * Every broken limiter setting is a start-up problem, reported by name together with the others; a
 * list that leaves some request without a policy, or two policies with one name, never starts.
 */
class RateLimitPropertiesTest {

  // The holds policy of application.yaml, then the catch-all.
  private static final List<String> GOOD =
      List.of(
          "seatwave.ratelimit.gateway-instances=3",
          "seatwave.ratelimit.store-quarantine=1s",
          "seatwave.ratelimit.policies[0].name=holds",
          "seatwave.ratelimit.policies[0].method=POST",
          "seatwave.ratelimit.policies[0].path-prefix=/v1/holds",
          "seatwave.ratelimit.policies[0].key=SUBJECT",
          "seatwave.ratelimit.policies[0].capacity=5",
          "seatwave.ratelimit.policies[0].refill-tokens=1",
          "seatwave.ratelimit.policies[0].refill-period=10s",
          "seatwave.ratelimit.policies[0].when-store-down=REFUSE",
          "seatwave.ratelimit.policies[0].is-shadow=false",
          "seatwave.ratelimit.policies[1].name=fan-requests",
          "seatwave.ratelimit.policies[1].method=ANY",
          "seatwave.ratelimit.policies[1].path-prefix=/",
          "seatwave.ratelimit.policies[1].key=SUBJECT",
          "seatwave.ratelimit.policies[1].capacity=30",
          "seatwave.ratelimit.policies[1].refill-tokens=10",
          "seatwave.ratelimit.policies[1].refill-period=1s",
          "seatwave.ratelimit.policies[1].when-store-down=LOCAL",
          "seatwave.ratelimit.policies[1].is-shadow=false");

  @Test
  void acceptsTheModulesPolicies() {
    runner(GOOD).run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void picksTheFirstPolicyThatCoversARequest() {
    runner(GOOD)
        .run(context -> {
          RateLimitProperties settings = context.getBean(RateLimitProperties.class);
          assertThat(settings.policyFor("POST", "/v1/holds").name()).isEqualTo("holds");
          assertThat(settings.policyFor("GET", "/v1/holds").name()).isEqualTo("fan-requests");
        });
  }

  @Test
  void refusesAListWhoseLastPolicyLeavesRequestsUncovered() {
    runner(with("seatwave.ratelimit.policies[1].path-prefix=/v1"))
        .run(context ->
            assertThat(problems(context.getStartupFailure()))
                .contains("the last policy must have method ANY and path-prefix /")
                .doesNotContain("unique"));
  }

  @Test
  void refusesTwoPoliciesWithOneName() {
    runner(with("seatwave.ratelimit.policies[0].name=fan-requests"))
        .run(context ->
            assertThat(problems(context.getStartupFailure()))
                .contains("policy names must be unique")
                .doesNotContain("last policy"));
  }

  @Test
  void refusesAQuarantineLongerThanAMinute() {
    runner(with("seatwave.ratelimit.store-quarantine=61s"))
        .run(context ->
            assertThat(problems(context.getStartupFailure()))
                .contains("storeQuarantine must be above 0 and at most 1m"));
  }

  @Test
  void refusesARefillPeriodShorterThanAMillisecond() {
    runner(with("seatwave.ratelimit.policies[0].refill-period=0s"))
        .run(context ->
            assertThat(problems(context.getStartupFailure()))
                .contains("refillPeriod must be from 1ms to 1d"));
  }

  @Test
  void refusesARefillPeriodLongerThanADay() {
    runner(with("seatwave.ratelimit.policies[0].refill-period=25h"))
        .run(context ->
            assertThat(problems(context.getStartupFailure()))
                .contains("refillPeriod must be from 1ms to 1d"));
  }

  @Test
  void refusesAnUnknownMethodAndANameARedisKeyShouldNotCarry() {
    runner(
            with(
                "seatwave.ratelimit.policies[0].method=TRACE",
                "seatwave.ratelimit.policies[0].name=Holds Policy"))
        .run(context ->
            assertThat(problems(context.getStartupFailure()))
                .contains("policies[0].method", "policies[0].name"));
  }

  @Test
  void reportsEveryBrokenRuleTogether() {
    runner(
            with(
                "seatwave.ratelimit.policies[0].capacity=0",
                "seatwave.ratelimit.gateway-instances=0",
                "seatwave.ratelimit.store-quarantine=0s"))
        .run(context ->
            assertThat(problems(context.getStartupFailure()))
                .contains(
                    "policies[0].capacity", "gatewayInstances", "storeQuarantine must be above 0"));
  }

  @Test
  void reportsAMissingSettingByName() {
    List<String> withoutQuarantine = new ArrayList<>(GOOD);
    withoutQuarantine.remove("seatwave.ratelimit.store-quarantine=1s");

    runner(withoutQuarantine)
        .run(context ->
            assertThat(problems(context.getStartupFailure())).contains("storeQuarantine"));
  }

  private static List<String> with(String... changes) {
    List<String> settings = new ArrayList<>(GOOD);
    for (String change : changes) {
      String key = change.substring(0, change.indexOf('=') + 1);
      settings.removeIf(setting -> setting.startsWith(key));
      settings.add(change);
    }
    return settings;
  }

  private static ApplicationContextRunner runner(List<String> settings) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
        .withUserConfiguration(Settings.class)
        .withPropertyValues(settings.toArray(String[]::new));
  }

  // Boot's binding failure names every broken field; its root cause holds the list.
  private static String problems(Throwable failure) {
    StringBuilder messages = new StringBuilder();
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      messages.append(cause.getMessage()).append('\n');
    }
    return messages.toString();
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(RateLimitProperties.class)
  static class Settings {}
}
