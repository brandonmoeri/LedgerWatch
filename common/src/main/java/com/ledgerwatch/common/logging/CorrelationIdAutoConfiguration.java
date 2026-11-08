package com.ledgerwatch.common.logging;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Wires correlation-id propagation into every LedgerWatch service that has {@code common} on its
 * classpath: the inbound filter, and the outbound interceptor on every {@code RestClient} built
 * from Boot's auto-configured {@code RestClient.Builder}.
 */
@AutoConfiguration
public class CorrelationIdAutoConfiguration {

  @Bean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
    FilterRegistrationBean<CorrelationIdFilter> registration =
        new FilterRegistrationBean<>(new CorrelationIdFilter());
    // Ahead of Spring Security (order -100), so its rejections carry the id too.
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
    return registration;
  }

  @Bean
  public RestClientCustomizer correlationIdRestClientCustomizer() {
    return builder -> builder.requestInterceptor(new CorrelationIdInterceptor());
  }
}
