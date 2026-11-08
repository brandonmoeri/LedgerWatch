package com.ledgerwatch.common.logging;

import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Forwards the current correlation id on outbound calls as {@value CorrelationId#HEADER}, so the
 * downstream service logs under the same id. Adds nothing outside a scope, and never overrides a
 * header the caller set explicitly.
 */
public class CorrelationIdInterceptor implements ClientHttpRequestInterceptor {

  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    String id = CorrelationId.current();
    if (id != null && !request.getHeaders().containsKey(CorrelationId.HEADER)) {
      request.getHeaders().set(CorrelationId.HEADER, id);
    }
    return execution.execute(request, body);
  }
}
