package com.ledgerwatch.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

class CorrelationIdInterceptorTest {

  private final CorrelationIdInterceptor interceptor = new CorrelationIdInterceptor();

  /** Sends {@code request} through the interceptor and returns what reached the wire. */
  private HttpRequest send(MockClientHttpRequest request) throws Exception {
    AtomicReference<HttpRequest> sent = new AtomicReference<>();
    ClientHttpRequestExecution execution =
        (req, body) -> {
          sent.set(req);
          return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        };
    interceptor.intercept(request, new byte[0], execution);
    return sent.get();
  }

  private static MockClientHttpRequest request() {
    return new MockClientHttpRequest(HttpMethod.POST, URI.create("http://account-service/x"));
  }

  @Test
  void forwardsTheCurrentId() throws Exception {
    try (CorrelationId.Scope scope = CorrelationId.open("abc-123")) {
      assertThat(send(request()).getHeaders().getFirst(CorrelationId.HEADER)).isEqualTo("abc-123");
    }
  }

  @Test
  void addsNothing_outsideAScope() throws Exception {
    assertThat(send(request()).getHeaders().containsKey(CorrelationId.HEADER)).isFalse();
  }

  @Test
  void keepsAnExplicitHeader() throws Exception {
    MockClientHttpRequest request = request();
    request.getHeaders().set(CorrelationId.HEADER, "explicit");
    try (CorrelationId.Scope scope = CorrelationId.open("abc-123")) {
      assertThat(send(request).getHeaders().get(CorrelationId.HEADER)).containsExactly("explicit");
    }
  }
}
