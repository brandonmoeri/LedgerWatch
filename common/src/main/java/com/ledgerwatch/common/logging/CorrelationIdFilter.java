package com.ledgerwatch.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds a correlation id to each inbound request: the caller's {@value CorrelationId#HEADER} if it
 * is valid, otherwise a new one. The id is in the MDC for the whole request and echoed in the
 * response header so a client can quote it.
 *
 * <p>Registered ahead of Spring Security so authentication failures are logged with the id too. It
 * also runs on the ERROR dispatch (Boot's {@code /error}), reusing the id from the original
 * dispatch rather than minting a second one.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

  static final String REQUEST_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id =
        request.getAttribute(REQUEST_ATTRIBUTE) instanceof String existing
            ? existing
            : CorrelationId.sanitize(request.getHeader(CorrelationId.HEADER));
    request.setAttribute(REQUEST_ATTRIBUTE, id);
    response.setHeader(CorrelationId.HEADER, id);
    try (CorrelationId.Scope scope = CorrelationId.open(id)) {
      chain.doFilter(request, response);
    }
  }

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }
}
