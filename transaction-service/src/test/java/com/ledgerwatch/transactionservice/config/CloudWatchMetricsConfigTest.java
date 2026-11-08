package com.ledgerwatch.transactionservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse;

@SuppressWarnings("null")
class CloudWatchMetricsConfigTest {

  @Test
  void isOffUnlessEnabled() {
    new ApplicationContextRunner()
        .withUserConfiguration(CloudWatchMetricsConfig.class)
        .run(context -> assertThat(context).doesNotHaveBean(CloudWatchMeterRegistry.class));
  }

  /** What actually reaches PutMetricData: only our meters, under the namespace, zeros included. */
  @Test
  void pushesOnlyLedgerwatchMeters() {
    CloudWatchAsyncClient client = mock(CloudWatchAsyncClient.class);
    when(client.putMetricData(any(PutMetricDataRequest.class)))
        .thenReturn(CompletableFuture.completedFuture(PutMetricDataResponse.builder().build()));
    CloudWatchMeterRegistry registry =
        new CloudWatchMetricsConfig()
            .cloudWatchMeterRegistry(
                new CloudWatchMetricsProperties(true, "LedgerWatchTest", Duration.ofMinutes(1)),
                client);

    Counter.builder("ledgerwatch.transactions.posted").tag("type", "DEBIT").register(registry);
    Gauge.builder("ledgerwatch.outbox.pending", new AtomicInteger(3), AtomicInteger::get)
        .register(registry);
    Counter.builder("http.server.requests").register(registry).increment();
    Gauge.builder("jvm.threads.live", () -> 42).register(registry);

    registry.close(); // publishes what it has

    ArgumentCaptor<PutMetricDataRequest> sent = ArgumentCaptor.forClass(PutMetricDataRequest.class);
    verify(client, atLeastOnce()).putMetricData(sent.capture());
    assertThat(sent.getAllValues()).allMatch(r -> r.namespace().equals("LedgerWatchTest"));
    List<MetricDatum> data =
        sent.getAllValues().stream().flatMap(r -> r.metricData().stream()).toList();
    assertThat(data)
        .extracting(MetricDatum::metricName)
        .containsExactlyInAnyOrder(
            "ledgerwatch.transactions.posted.count", "ledgerwatch.outbox.pending.value");
    assertThat(data)
        .filteredOn(d -> d.metricName().equals("ledgerwatch.transactions.posted.count"))
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.value()).isZero();
              assertThat(d.dimensions())
                  .anySatisfy(
                      dim -> {
                        assertThat(dim.name()).isEqualTo("type");
                        assertThat(dim.value()).isEqualTo("DEBIT");
                      });
            });
  }
}
