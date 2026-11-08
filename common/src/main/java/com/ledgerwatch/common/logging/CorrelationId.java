package com.ledgerwatch.common.logging;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * The id that ties together every log line, in every service, caused by one inbound request.
 *
 * <p>It travels between services in the {@value #HEADER} header and lives in the SLF4J {@link MDC}
 * under {@value #MDC_KEY} while work for it runs, so the JSON log encoder emits it on each line.
 * {@link CorrelationIdFilter} sets it for inbound HTTP requests and {@link
 * CorrelationIdInterceptor} forwards it on outbound {@code RestClient} calls. Work that runs
 * outside a request, such as a scheduled job, opens its own {@link #open scope}.
 */
public final class CorrelationId {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";
  public static final int MAX_LENGTH = 128;

  // The header comes from outside, so it is checked before it reaches logs: no whitespace or
  // control characters (log injection), and a bounded length.
  private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:-]{1," + MAX_LENGTH + "}");

  private CorrelationId() {}

  public static String generate() {
    return UUID.randomUUID().toString();
  }

  public static boolean isValid(String id) {
    return id != null && VALID.matcher(id).matches();
  }

  /** Returns {@code candidate} if it is a usable id, or a freshly generated one if not. */
  public static String sanitize(String candidate) {
    return isValid(candidate) ? candidate : generate();
  }

  /** The id of the work running on this thread, or null outside any scope. */
  public static String current() {
    return MDC.get(MDC_KEY);
  }

  /**
   * Puts {@code id} (sanitized) in the MDC until the returned scope is closed, which restores
   * whatever was there before. Use with try-with-resources.
   */
  public static Scope open(String id) {
    String previous = MDC.get(MDC_KEY);
    MDC.put(MDC_KEY, sanitize(id));
    return () -> {
      if (previous == null) {
        MDC.remove(MDC_KEY);
      } else {
        MDC.put(MDC_KEY, previous);
      }
    };
  }

  /** An {@link AutoCloseable} whose {@code close} doesn't throw. */
  @FunctionalInterface
  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
