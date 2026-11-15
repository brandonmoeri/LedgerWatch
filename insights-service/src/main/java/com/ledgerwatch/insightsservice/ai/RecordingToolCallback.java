package com.ledgerwatch.insightsservice.ai;

import java.util.Arrays;
import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Wraps a tool so each call is timed and recorded in the request's {@link ToolCallRecorder}. The
 * tool itself, its definition and its result are unchanged; a call that throws is recorded as
 * failed and the exception is passed on for Spring AI to turn into an error tool result.
 */
public final class RecordingToolCallback implements ToolCallback {

  private final ToolCallback delegate;

  /** The {@code @Tool} methods of {@code toolObjects}, each wrapped to record its calls. */
  public static List<ToolCallback> forTools(Object... toolObjects) {
    return Arrays.stream(ToolCallbacks.from(toolObjects))
        .<ToolCallback>map(RecordingToolCallback::new)
        .toList();
  }

  RecordingToolCallback(ToolCallback delegate) {
    this.delegate = delegate;
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return delegate.getToolDefinition();
  }

  @Override
  public ToolMetadata getToolMetadata() {
    return delegate.getToolMetadata();
  }

  @Override
  public String call(String toolInput) {
    return call(toolInput, null);
  }

  @Override
  public String call(String toolInput, ToolContext toolContext) {
    long start = System.nanoTime();
    boolean succeeded = false;
    try {
      String result =
          toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
      succeeded = true;
      return result;
    } finally {
      long durationMs = (System.nanoTime() - start) / 1_000_000;
      boolean ok = succeeded;
      ToolCallRecorder.from(toolContext)
          .ifPresent(r -> r.record(getToolDefinition().name(), durationMs, ok));
    }
  }
}
