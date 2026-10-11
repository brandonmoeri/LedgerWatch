package com.ledgerwatch.transactionservice.client;

import com.ledgerwatch.common.error.InsufficientFundsException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
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

/**
 * HTTP client for account-service's {@code /internal/**} endpoints.
 *
 * <p>Calls go through the {@code accountService} retry and circuit breaker (configured under {@code
 * resilience4j.*}). Retry is the outer layer, so the breaker records every attempt. Only {@link
 * AccountServiceUnavailableException} (I/O failure, timeout, 5xx) is retried or counted as a
 * failure; 4xx answers are final.
 */
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
   * Adds a signed {@code delta} to the account's balance. account-service applies a given {@code
   * adjustmentId} at most once, which is what makes retrying a timed-out or 5xx call safe.
   *
   * @throws NoSuchElementException the account does not exist
   * @throws IllegalStateException the account is FROZEN/CLOSED or was modified concurrently
   * @throws InsufficientFundsException a debit would take the balance below zero
   * @throws AccountServiceUnavailableException account-service is unreachable, timed out, or
   *     returned 5xx on every attempt
   * @throws AccountServiceException account-service returned another unexpected status
   * @throws CallNotPermittedException the circuit breaker is open; no request was sent
   */
  @Retry(name = "accountService")
  @CircuitBreaker(name = "accountService")
  public void applyBalanceDelta(UUID accountId, BigDecimal delta, UUID adjustmentId) {
    try {
      restClient
          .post()
          .uri("/internal/accounts/{id}/balance-adjustments", accountId)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new BalanceAdjustment(adjustmentId, delta))
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
              // The internal endpoint returns 422 only for insufficient funds.
              status -> status.isSameCodeAs(HttpStatus.UNPROCESSABLE_ENTITY),
              (request, response) -> {
                throw new InsufficientFundsException(accountId, delta.negate());
              })
          .onStatus(
              HttpStatusCode::is5xxServerError,
              (request, response) -> {
                throw new AccountServiceUnavailableException(
                    "account-service returned " + response.getStatusCode().value());
              })
          .onStatus(
              HttpStatusCode::isError,
              (request, response) -> {
                throw new AccountServiceException(
                    "account-service returned " + response.getStatusCode().value());
              })
          .toBodilessEntity();
    } catch (ResourceAccessException e) {
      throw new AccountServiceUnavailableException(
          "account-service is unreachable or timed out", e);
    }
  }

  record BalanceAdjustment(UUID adjustmentId, BigDecimal delta) {}
}
