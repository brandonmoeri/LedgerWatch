package com.ledgerwatch.insightsservice.ai;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;

/**
 * One question to Claude, one text answer back.
 *
 * <p>Use this rather than {@code chatClient.prompt()...call().content()}: Spring AI turns each
 * response content block into its own {@link Generation}, and Claude Opus 5.5 always thinks, so the
 * first generation is a thinking block (with empty text) and {@code content()} returns "". This
 * joins the text blocks instead, and turns a refusal or a truncated answer into an exception rather
 * than a blank or half-finished reply.
 */
@Component
public class InsightsChat {

  private final ChatClient chatClient;

  public InsightsChat(ChatClient insightsChatClient) {
    this.chatClient = insightsChatClient;
  }

  public String ask(String question) {
    ChatResponse response = chatClient.prompt().user(question).call().chatResponse();
    if (response == null) {
      throw new InsightsGenerationException(null, "Claude returned no response");
    }

    String stopReason = response.getMetadata().get("stop-reason");
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

  /** Thinking blocks carry a "signature", redacted thinking "data"; tool calls have no text. */
  private static boolean isText(AssistantMessage message) {
    Map<String, Object> properties = message.getMetadata();
    return !properties.containsKey("signature")
        && !properties.containsKey("data")
        && !message.hasToolCalls();
  }
}
