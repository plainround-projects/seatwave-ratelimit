package io.plainround.seatwave.ratelimit.booking.shedding;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Puts the load shedder in front of everything else booking does, for every request. */
@Configuration(proxyBeanMethods = false)
public class SheddingConfiguration {

  /**
   * The shedder runs first: a request that will be shed should cost as little as possible.
   *
   * @param settings the in-flight limit
   * @param meters the meter registry
   * @return the registration
   */
  @Bean
  FilterRegistrationBean<LoadShedFilter> loadShedFilter(
      SheddingProperties settings, MeterRegistry meters) {
    FilterRegistrationBean<LoadShedFilter> registration =
        new FilterRegistrationBean<>(new LoadShedFilter(settings, meters));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }
}
