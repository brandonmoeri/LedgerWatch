package com.ledgerwatch.insightsservice.client;

import com.ledgerwatch.insightsservice.client.LedgerViews.Account;
import com.ledgerwatch.insightsservice.client.LedgerViews.FraudFlag;
import com.ledgerwatch.insightsservice.client.LedgerViews.Page;
import com.ledgerwatch.insightsservice.client.LedgerViews.Transaction;
import com.ledgerwatch.insightsservice.client.LedgerViews.TransactionStatus;
import com.ledgerwatch.insightsservice.client.LedgerViews.TransactionType;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Read-only calls to account-service and transaction-service, made as the end user.
 *
 * <p>Every method takes the caller's bearer token and sends it unchanged, so the ledger services
 * apply exactly the access checks they would apply to that user calling them directly. This class
 * holds no token of its own and has no fallback identity: without the caller's token there is no
 * call. It only issues GETs.
 *
 * <p>Query values are passed as URI template variables, which Spring encodes strictly, so a value
 * Claude chose (such as a description containing {@code &accountId=...}) can't add parameters.
 */
@Component
@EnableConfigurationProperties(LedgerServicesProperties.class)
public class LedgerApiClient {

  static final int MAX_PAGE_SIZE = 50;
  static final int DEFAULT_PAGE_SIZE = 20;

  private static final ParameterizedTypeReference<Page<Transaction>> TRANSACTION_PAGE =
      new ParameterizedTypeReference<>() {};
  private static final ParameterizedTypeReference<Page<FraudFlag>> FLAG_PAGE =
      new ParameterizedTypeReference<>() {};

  private final RestClient accounts;
  private final RestClient transactions;

  public LedgerApiClient(RestClient.Builder builder, LedgerServicesProperties properties) {
    // Our own request factory: the Boot-wide spring.http.client.read-timeout is sized for Claude
    // (minutes), far too long to wait on a ledger read.
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.readTimeout());

    // clone(): the builder is shared, and baseUrl would otherwise leak from one client to the next.
    this.accounts =
        builder
            .clone()
            .baseUrl(properties.accountServiceUrl().toString())
            .requestFactory(requestFactory)
            .build();
    this.transactions =
        builder
            .clone()
            .baseUrl(properties.transactionServiceUrl().toString())
            .requestFactory(requestFactory)
            .build();
  }

  public Account getAccount(UUID accountId, String callerToken) {
    return call(
        "account-service",
        "Account " + accountId,
        () ->
            accounts
                .get()
                .uri("/accounts/{id}", accountId)
                .headers(h -> h.setBearerAuth(requireToken(callerToken)))
                .retrieve()
                .onStatus(HttpStatusCode::isError, LedgerApiClient::noBody)
                .body(Account.class));
  }

  public Page<Transaction> searchTransactions(
      UUID accountId,
      TransactionType type,
      TransactionStatus status,
      String description,
      Instant createdFrom,
      Instant createdTo,
      Integer page,
      Integer size,
      String callerToken) {
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("accountId", accountId);
    vars.put("page", page == null ? 0 : Math.max(page, 0));
    vars.put("size", size == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    if (type != null) vars.put("type", type);
    if (status != null) vars.put("status", status);
    if (description != null && !description.isBlank()) vars.put("description", description);
    if (createdFrom != null) vars.put("createdFrom", createdFrom);
    if (createdTo != null) vars.put("createdTo", createdTo);

    StringBuilder template = new StringBuilder("/transactions");
    char separator = '?';
    for (String name : vars.keySet()) {
      template.append(separator).append(name).append("={").append(name).append('}');
      separator = '&';
    }

    return call(
        "transaction-service",
        "Account " + accountId,
        () ->
            transactions
                .get()
                .uri(template.toString(), vars)
                .headers(h -> h.setBearerAuth(requireToken(callerToken)))
                .retrieve()
                .onStatus(HttpStatusCode::isError, LedgerApiClient::noBody)
                .body(TRANSACTION_PAGE));
  }

  public Page<FraudFlag> getFlags(UUID accountId, Integer page, Integer size, String callerToken) {
    int p = page == null ? 0 : Math.max(page, 0);
    int s = size == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    return call(
        "transaction-service",
        "Account " + accountId,
        () ->
            transactions
                .get()
                .uri("/accounts/{id}/flags?page={page}&size={size}", accountId, p, s)
                .headers(h -> h.setBearerAuth(requireToken(callerToken)))
                .retrieve()
                .onStatus(HttpStatusCode::isError, LedgerApiClient::noBody)
                .body(FLAG_PAGE));
  }

  /** Refuses to send a request at all, rather than send one without the user's identity. */
  private static String requireToken(String callerToken) {
    if (callerToken == null || callerToken.isBlank()) {
      throw new IllegalStateException("No caller token: ledger data is only read as the user");
    }
    return callerToken;
  }

  /** Stops RestClient's default handler, which would put the response body in the message. */
  private static void noBody(HttpRequest request, ClientHttpResponse response) throws IOException {
    throw new StatusException(response.getStatusCode());
  }

  private static <T> T call(String service, String subject, Supplier<T> call) {
    try {
      return call.get();
    } catch (StatusException e) {
      HttpStatusCode status = e.status;
      if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
        throw new NoSuchElementException(subject + " was not found");
      }
      if (status.isSameCodeAs(HttpStatus.UNAUTHORIZED)
          || status.isSameCodeAs(HttpStatus.FORBIDDEN)) {
        throw new LedgerServiceException(
            "The user is not allowed to read this data ("
                + service
                + " returned "
                + status.value()
                + ")");
      }
      throw new LedgerServiceException(service + " returned " + status.value());
    } catch (ResourceAccessException e) {
      throw new LedgerServiceException(service + " is unreachable or timed out", e);
    }
  }

  private static final class StatusException extends RuntimeException {
    private final HttpStatusCode status;

    StatusException(HttpStatusCode status) {
      super(null, null, false, false);
      this.status = status;
    }
  }
}
