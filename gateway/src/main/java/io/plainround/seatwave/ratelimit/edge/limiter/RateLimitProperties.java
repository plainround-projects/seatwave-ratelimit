package io.plainround.seatwave.ratelimit.edge.limiter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The limiter's settings, bound from {@code seatwave.ratelimit.*} and checked at start-up; Spring
 * Boot reports every broken rule together and the gateway does not start.
 *
 * @param policies the limits, first match wins; the last one covers every request
 * @param gatewayInstances how many gateway instances share the limits; a local fallback bucket gets
 *     this share of each limit, so the instances together stay near the shared limit
 * @param storeQuarantine after a store failure, how long this instance decides without asking the
 *     store, so a dead Redis costs one timeout per quarantine and not one per request
 */
@Validated
@ConfigurationProperties("seatwave.ratelimit")
public record RateLimitProperties(
    @NotEmpty List<@Valid LimitPolicy> policies,
    @Min(1) @Max(1_000) int gatewayInstances,
    @NotNull Duration storeQuarantine) {

  private static final Duration LONGEST_QUARANTINE = Duration.ofMinutes(1);

  /**
   * The last policy must cover every request, so no request passes without a limit.
   *
   * @return true when the last policy is ANY on {@code /}, or when the list is missing
   */
  @AssertTrue(message = "the last policy must have method ANY and path-prefix /")
  public boolean isLastPolicyCatchAll() {
    if (policies == null || policies.isEmpty() || policies.getLast() == null) {
      return true;
    }
    LimitPolicy last = policies.getLast();
    return "ANY".equals(last.method()) && "/".equals(last.pathPrefix());
  }

  /**
   * Policy names must be unique, because the name is part of the Redis key.
   *
   * @return true when no two policies share a name
   */
  @AssertTrue(message = "policy names must be unique")
  public boolean isEveryNameUnique() {
    if (policies == null) {
      return true;
    }
    return policies.stream().map(LimitPolicy::name).distinct().count() == policies.size();
  }

  /**
   * The quarantine must be from just above 0 to one minute.
   *
   * @return true when it is in range or missing
   */
  @AssertTrue(message = "storeQuarantine must be above 0 and at most 1m")
  public boolean isStoreQuarantineInRange() {
    return storeQuarantine == null
        || (storeQuarantine.isPositive() && storeQuarantine.compareTo(LONGEST_QUARANTINE) <= 0);
  }

  /**
   * The first policy that covers a request; the catch-all guarantees there is one.
   *
   * @param method the request's method
   * @param path the request's path
   * @return the policy
   */
  public LimitPolicy policyFor(String method, String path) {
    return policies.stream()
        .filter(policy -> policy.covers(method, path))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("the catch-all policy is missing"));
  }
}
