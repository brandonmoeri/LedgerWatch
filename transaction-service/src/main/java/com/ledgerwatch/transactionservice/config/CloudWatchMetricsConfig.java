package com.ledgerwatch.transactionservice.config;

import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.config.MeterFilter;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

/**
 * Pushes the service's own {@code ledgerwatch.*} meters to CloudWatch every {@code
 * metrics.cloudwatch.step} when {@code metrics.cloudwatch.enabled=true}, which Terraform sets on
 * ECS. Spring Boot 3.3 has no CloudWatch auto-configuration, so the registry is declared here. Boot
 * still treats it like its own: it applies {@code management.metrics.*} (such as the {@code
 * application} common tag) and binds every {@code MeterBinder} to it, and the Simple registry backs
 * off.
 *
 * <p>Everything else is filtered out before it reaches CloudWatch, because each name and dimension
 * combination is billed as a separate custom metric, and Boot's JVM, HTTP, Hikari and Resilience4j
 * meters would add a few hundred. With the filter, {@code /actuator/metrics} on ECS lists only the
 * {@code ledgerwatch.*} meters too.
 *
 * <p>Credentials and region come from the AWS SDK's default chains: the ECS task role and {@code
 * AWS_REGION}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "metrics.cloudwatch.enabled", havingValue = "true")
@EnableConfigurationProperties(CloudWatchMetricsProperties.class)
public class CloudWatchMetricsConfig {

  static final String METER_PREFIX = "ledgerwatch.";

  @Bean(destroyMethod = "close")
  CloudWatchAsyncClient cloudWatchAsyncClient() {
    return CloudWatchAsyncClient.create();
  }

  /** Closed before the client it depends on, so shutdown still pushes the last partial step. */
  @Bean
  CloudWatchMeterRegistry cloudWatchMeterRegistry(
      CloudWatchMetricsProperties properties, CloudWatchAsyncClient client) {
    CloudWatchConfig config =
        new CloudWatchConfig() {
          @Override
          public String get(String key) {
            return null;
          }

          @Override
          public String namespace() {
            return properties.namespace();
          }

          @Override
          public Duration step() {
            return properties.step();
          }
        };
    CloudWatchMeterRegistry registry = new CloudWatchMeterRegistry(config, Clock.SYSTEM, client);
    registry
        .config()
        .meterFilter(MeterFilter.denyUnless(id -> id.getName().startsWith(METER_PREFIX)));
    return registry;
  }
}
