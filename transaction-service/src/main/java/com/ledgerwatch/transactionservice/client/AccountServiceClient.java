package com.ledgerwatch.transactionservice.client;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** HTTP client for account-service's {@code /internal/**} endpoints. */
@Component
@EnableConfigurationProperties(AccountServiceProperties.class)
public class AccountServiceClient {

  private final RestClient restClient;

  public AccountServiceClient(
      RestClient.Builder builder,
      AccountServiceProperties properties,
      ServiceTokenProvider tokenProvider) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            // Skip the h2c upgrade attempt the JDK client makes on plain http:// by default.
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.readTimeout());

    this.restClient =
        builder
            .baseUrl(properties.baseUrl().toString())
            .requestFactory(requestFactory)
            .requestInterceptor(
                (request, body, execution) -> {
                  request.getHeaders().setBearerAuth(tokenProvider.token());
                  return execution.execute(request, body);
                })
            .build();
  }

  /**
   * Adds a signed {@code delta} to the account's balance.
   *
   * @throws NoSuchElementException the account does not exist
   * @throws IllegalStateException the account is FROZEN/CLOSED or was modified concurrently
   * @throws AccountServiceException account-service is unreachable, timed out, or failed
   */
  public void applyBalanceDelta(UUID accountId, BigDecimal delta) {
    try {
      restClient
          .post()
          .uri("/internal/accounts/{id}/balance-adjustments", accountId)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new BalanceAdjustment(delta))
          .retrieve()
          .onStatus(
              status -> status.isSameCodeAs(HttpStatus.NOT_FOUND),
              (request, response) -> {
                throw new NoSuchElementException("Account not found: " + accountId);
              })
          .onStatus(
              status -> status.isSameCodeAs(HttpStatus.CONFLICT),
              (request, response) -> {
                throw new IllegalStateException(
                    "Account "
                        + accountId
                        + " rejected the balance change (frozen, closed, or modified"
                        + " concurrently)");
              })
          .onStatus(
              HttpStatusCode::isError,
              (request, response) -> {
                throw new AccountServiceException(
                    "account-service returned " + response.getStatusCode().value());
              })
          .toBodilessEntity();
    } catch (ResourceAccessException e) {
      throw new AccountServiceException("account-service is unreachable or timed out", e);
    }
  }

  record BalanceAdjustment(BigDecimal delta) {}
}
