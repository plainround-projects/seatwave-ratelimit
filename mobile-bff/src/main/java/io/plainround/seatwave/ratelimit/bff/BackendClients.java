package io.plainround.seatwave.ratelimit.bff;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The two long-lived clients, one per service, created once and shared by every request (guideline
 * 31). Each has its own read timeout, because the seat map and the fan's holds have different
 * budgets.
 */
@Configuration(proxyBeanMethods = false)
public class BackendClients {

  /**
   * One JDK HTTP client for both services; Spring closes it when the service stops.
   *
   * @param settings the connect timeout
   * @return the shared client
   */
  @Bean
  HttpClient backendHttp(MobileBffProperties settings) {
    return HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).build();
  }

  /**
   * The catalog's client.
   *
   * @param http the shared JDK client
   * @param settings the catalog's address and timeout
   * @return a client whose every call ends within the catalog timeout
   */
  @Bean
  RestClient catalogRestClient(HttpClient http, MobileBffProperties settings) {
    return client(http, settings.catalogUri().toString(), settings.catalogTimeout());
  }

  /**
   * The booking service's client.
   *
   * @param http the shared JDK client
   * @param settings the booking service's address and timeout
   * @return a client whose every call ends within the booking timeout
   */
  @Bean
  RestClient bookingRestClient(HttpClient http, MobileBffProperties settings) {
    return client(http, settings.bookingUri().toString(), settings.bookingTimeout());
  }

  private static RestClient client(HttpClient http, String baseUri, Duration timeout) {
    JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
    // The JDK client's request timeout: no answer within it, and the call fails, so a slow
    // service can never hold the screen longer than its budget.
    requests.setReadTimeout(timeout);
    return RestClient.builder().baseUrl(baseUri).requestFactory(requests).build();
  }
}
