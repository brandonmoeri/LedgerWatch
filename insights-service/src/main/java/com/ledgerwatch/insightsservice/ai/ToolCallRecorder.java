package com.ledgerwatch.insightsservice.ai;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Collects the tool calls Claude made while answering one question, for the request log.
 *
 * <p>{@link InsightsChat} creates one per question and puts it in the {@link ToolContext} under
 * {@link #CONTEXT_KEY}; {@link RecordingToolCallback} adds to it. Like the caller's token, the tool
 * context never reaches Claude.
 */
public final class ToolCallRecorder {

  /** Tool-context key for the current request's recorder. */
  public static final String CONTEXT_KEY = "ledgerwatch.toolCallRecorder";

  /** One tool call: which tool, how long it took, and whether it returned or threw. */
  public record ToolCall(String name, long durationMs, boolean succeeded) {

    /** Compact form for text-format log lines; JSON lines serialize the fields instead. */
    @Override
    public String toString() {
      return name + "(" + durationMs + "ms" + (succeeded ? "" : ", failed") + ")";
    }
  }

  private final List<ToolCall> calls = new CopyOnWriteArrayList<>();

  void record(String name, long durationMs, boolean succeeded) {
    calls.add(new ToolCall(name, durationMs, succeeded));
  }

  public List<ToolCall> calls() {
    return List.copyOf(calls);
  }

  static Optional<ToolCallRecorder> from(ToolContext toolContext) {
    Object recorder = toolContext == null ? null : toolContext.getContext().get(CONTEXT_KEY);
    return recorder instanceof ToolCallRecorder r ? Optional.of(r) : Optional.empty();
  }
}
