package io.plainround.seatwave.ratelimit.edge.limiter;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires the limiter into the gateway. The filter is registered for every request, right after
 * Module 11's identity filter, so no route can be added without passing it: the place where no code
 * path can bypass the per-user bound.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfiguration {

  // Spring Security's chain runs at DEFAULT_FILTER_ORDER and the identity filter one after it.
  private static final int AFTER_IDENTITY_FILTER =
      SecurityFilterProperties.DEFAULT_FILTER_ORDER + 2;

  /**
   * The shared bucket in Redis; Spring closes it when the gateway stops.
   *
   * @param redis Spring Boot's template
   * @param redisSettings Spring Boot's Redis settings, for {@code spring.data.redis.timeout}
   * @return the bucket
   */
  @Bean
  RedisTokenBucket redisTokenBucket(StringRedisTemplate redis, DataRedisProperties redisSettings) {
    Duration callTimeout =
        Objects.requireNonNull(
            redisSettings.getTimeout(),
            "spring.data.redis.timeout must be set: it bounds every store call of the limiter");
    return new RedisTokenBucket(redis, callTimeout);
  }

  /**
   * The local fallback.
   *
   * @param settings the limiter's settings
   * @param clock the gateway's clock (Module 11's bean)
   * @return the fallback buckets
   */
  @Bean
  LocalBuckets localBuckets(RateLimitProperties settings, Clock clock) {
    return new LocalBuckets(settings.gatewayInstances(), clock);
  }

  /**
   * The filter, for every request, after the identity filter.
   *
   * @param settings the policies
   * @param shared the Redis bucket
   * @param local the fallback
   * @param meters the meter registry
   * @param clock the gateway's clock
   * @return the registration
   */
  @Bean
  FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
      RateLimitProperties settings,
      RedisTokenBucket shared,
      LocalBuckets local,
      MeterRegistry meters,
      Clock clock) {
    FilterRegistrationBean<RateLimitFilter> registration =
        new FilterRegistrationBean<>(new RateLimitFilter(settings, shared, local, meters, clock));
    registration.setOrder(AFTER_IDENTITY_FILTER);
    return registration;
  }
}
