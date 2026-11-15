package com.ledgerwatch.insightsservice.client;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where account-service and transaction-service live, and how long to wait for them. */
@ConfigurationProperties("ledger-services")
public record LedgerServicesProperties(
    URI accountServiceUrl,
    URI transactionServiceUrl,
    Duration connectTimeout,
    Duration readTimeout) {

  public LedgerServicesProperties {
    Objects.requireNonNull(accountServiceUrl, "ledger-services.account-service-url");
    Objects.requireNonNull(transactionServiceUrl, "ledger-services.transaction-service-url");
    Objects.requireNonNull(connectTimeout, "ledger-services.connect-timeout");
    Objects.requireNonNull(readTimeout, "ledger-services.read-timeout");
  }
}
