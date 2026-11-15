package com.ledgerwatch.insightsservice.ai;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * One question to Claude, one text answer back.
 *
 * <p>Use this rather than {@code chatClient.prompt()...call().content()}: Spring AI turns each
 * response content block into its own {@link Generation}, and Claude Opus 5.5 always thinks, so the
 * first generation is a thinking block (with empty text) and {@code content()} returns "". This
 * joins the text blocks instead, and turns a refusal or a truncated answer into an exception rather
 * than a blank or half-finished reply.
 *
 * <p>Claude may call the {@link LedgerTools} while answering. They read the ledger as {@code
 * caller}: the caller's token goes into the tool context, which Spring AI hands to the tools but
 * never sends to Claude.
 *
 * <p>Every question, answered or not, produces one "Insights request" log line with its outcome,
 * latency, token usage (summed over every model call in the tool loop) and the tools called. The
 * question and answer are not logged: they are the customer's financial data.
 */
@Component
public class InsightsChat {

  private static final Logger log = LoggerFactory.getLogger(InsightsChat.class);

  private final ChatClient chatClient;

  public InsightsChat(ChatClient insightsChatClient) {
    this.chatClient = insightsChatClient;
  }

  public String ask(String question, Jwt caller) {
    Objects.requireNonNull(caller, "caller");
    ToolCallRecorder toolCalls = new ToolCallRecorder();
    long start = System.nanoTime();
    ChatResponse response = null;
    try {
      response =
          chatClient
              .prompt()
              .user(question)
              .toolContext(
                  Map.of(
                      LedgerTools.CALLER_TOKEN, caller.getTokenValue(),
                      ToolCallRecorder.CONTEXT_KEY, toolCalls))
              .call()
              .chatResponse();
      String answer = answerFrom(response);
      logRequest("answered", response, start, toolCalls, null);
      return answer;
    } catch (InsightsGenerationException e) {
      logRequest(
          Objects.requireNonNullElse(e.getStopReason(), "no_response"),
          response,
          start,
          toolCalls,
          null);
      throw e;
    } catch (RuntimeException e) {
      logRequest("error", response, start, toolCalls, e);
      throw e;
    }
  }

  private static String answerFrom(ChatResponse response) {
    if (response == null) {
      throw new InsightsGenerationException(null, "Claude returned no response");
    }

    String stopReason = stopReason(response);
    if ("refusal".equals(stopReason)) {
      throw new InsightsGenerationException(stopReason, "Claude declined to answer");
    }
    if ("max_tokens".equals(stopReason)) {
      throw new InsightsGenerationException(
          stopReason, "Claude's answer was cut off at the max-tokens limit");
    }

    return response.getResults().stream()
        .map(Generation::getOutput)
        .filter(InsightsChat::isText)
        .map(AssistantMessage::getText)
        .filter(Objects::nonNull)
        .collect(Collectors.joining());
  }

  private static String stopReason(ChatResponse response) {
    return response.getMetadata().get("stop-reason");
  }

  /** Thinking blocks carry a "signature", redacted thinking "data"; tool calls have no text. */
  private static boolean isText(AssistantMessage message) {
    Map<String, Object> properties = message.getMetadata();
    return !properties.containsKey("signature")
        && !properties.containsKey("data")
        && !message.hasToolCalls();
  }

  /**
   * Structured arguments: JSON log lines get each as its own field, text lines render key=value.
   * Token counts are null when Claude never answered (the API call itself failed).
   */
  private static void logRequest(
      String outcome,
      ChatResponse response,
      long startNanos,
      ToolCallRecorder toolCalls,
      Throwable error) {
    long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
    Usage usage = response == null ? null : response.getMetadata().getUsage();
    var calls = toolCalls.calls();
    Object[] args = {
      kv("outcome", outcome),
      kv("latencyMs", latencyMs),
      kv("model", response == null ? null : response.getMetadata().getModel()),
      kv("stopReason", response == null ? null : stopReason(response)),
      kv("inputTokens", usage == null ? null : usage.getPromptTokens()),
      kv("outputTokens", usage == null ? null : usage.getCompletionTokens()),
      kv("totalTokens", usage == null ? null : usage.getTotalTokens()),
      kv("toolCallCount", calls.size()),
      kv("toolCalls", calls),
      error
    };
    String message = "Insights request {} {} {} {} {} {} {} {} {}";
    if ("answered".equals(outcome)) {
      log.info(message, args);
    } else {
      log.warn(message, args);
    }
  }
}
