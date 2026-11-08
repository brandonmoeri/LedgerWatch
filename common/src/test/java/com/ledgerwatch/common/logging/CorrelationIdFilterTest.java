package com.ledgerwatch.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.DispatcherType;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  /** Runs the filter and returns the correlation id the downstream chain saw in the MDC. */
  private String run(MockHttpServletRequest request, MockHttpServletResponse response)
      throws Exception {
    AtomicReference<String> seen = new AtomicReference<>();
    filter.doFilter(
        request,
        response,
        new MockFilterChain() {
          @Override
          public void doFilter(
              jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            seen.set(CorrelationId.current());
          }
        });
    return seen.get();
  }

  @Test
  void usesTheCallersId_andEchoesIt() throws Exception {
    var request = new MockHttpServletRequest("GET", "/accounts");
    request.addHeader(CorrelationId.HEADER, "abc-123");
    var response = new MockHttpServletResponse();

    assertThat(run(request, response)).isEqualTo("abc-123");
    assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("abc-123");
    assertThat(CorrelationId.current()).as("MDC is cleared after the request").isNull();
  }

  @Test
  void generatesAnId_whenTheHeaderIsMissing() throws Exception {
    var response = new MockHttpServletResponse();

    String id = run(new MockHttpServletRequest("GET", "/accounts"), response);

    assertThat(CorrelationId.isValid(id)).isTrue();
    assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo(id);
  }

  @Test
  void replacesAnUnsafeHeader() throws Exception {
    var request = new MockHttpServletRequest("GET", "/accounts");
    request.addHeader(CorrelationId.HEADER, "forged\nlog line");
    var response = new MockHttpServletResponse();

    String id = run(request, response);

    assertThat(id).isNotEqualTo("forged\nlog line");
    assertThat(CorrelationId.isValid(id)).isTrue();
  }

  @Test
  void errorDispatch_reusesTheOriginalRequestsId() throws Exception {
    var request = new MockHttpServletRequest("GET", "/accounts");
    String original = run(request, new MockHttpServletResponse());

    // Boot forwards failures to /error on the same request object, without the header.
    request.setDispatcherType(DispatcherType.ERROR);
    request.removeAttribute(CorrelationIdFilter.class.getName() + ".FILTERED");
    String onError = run(request, new MockHttpServletResponse());

    assertThat(onError).isEqualTo(original);
  }
}
