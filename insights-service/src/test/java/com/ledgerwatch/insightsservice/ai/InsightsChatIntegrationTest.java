package com.ledgerwatch.insightsservice.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Runs the real ChatClient wiring against WireMock standing in for api.anthropic.com. */
@SpringBootTest
class InsightsChatIntegrationTest {

  static final WireMockServer anthropic = new WireMockServer(wireMockConfig().dynamicPort());

  static {
    anthropic.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.ai.anthropic.base-url", anthropic::baseUrl);
    registry.add("spring.ai.anthropic.api-key", () -> "test-key");
    registry.add("spring.ai.retry.max-attempts", () -> "1");
    registry.add("jwt.secret", () -> "test-secret-that-is-at-least-32-bytes-long");
  }

  @AfterAll
  static void stop() {
    anthropic.stop();
  }

  @Autowired InsightsChat insightsChat;

  static final Jwt CALLER =
      Jwt.withTokenValue("caller-token").header("alg", "HS256").subject("analyst").build();

  @BeforeEach
  void reset() {
    anthropic.resetAll();
  }

  @Test
  void returnsTheTextBlocksAndSkipsThinking() {
    respondWith(
        "end_turn",
        """
        {"type": "thinking", "thinking": "", "signature": "sig-1"},
        {"type": "text", "text": "Groceries are up 12%"},
        {"type": "text", "text": " on last month."}
        """);

    assertThat(insightsChat.ask("How is my grocery spending?", CALLER))
        .isEqualTo("Groceries are up 12% on last month.");
  }

  @Test
  void sendsTheConfiguredModelKeyAndSystemPromptWithoutSamplingParameters() {
    respondWith("end_turn", "{\"type\": \"text\", \"text\": \"ok\"}");

    insightsChat.ask("Hello", CALLER);

    anthropic.verify(
        postRequestedFor(urlEqualTo("/v1/messages"))
            .withHeader("x-api-key", equalTo("test-key"))
            .withRequestBody(matchingJsonPath("$.model", equalTo("claude-opus-5-5")))
            .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("16000")))
            .withRequestBody(matchingJsonPath("$.system", containing("LedgerWatch")))
            .withRequestBody(notContaining("\"temperature\""))
            .withRequestBody(notContaining("\"top_p\""))
            .withRequestBody(notContaining("\"top_k\"")));
  }

  @Test
  void refusalIsAnErrorNotABlankAnswer() {
    respondWith("refusal", "");

    assertThatThrownBy(() -> insightsChat.ask("...", CALLER))
        .isInstanceOf(InsightsGenerationException.class)
        .extracting("stopReason")
        .isEqualTo("refusal");
  }

  @Test
  void truncatedAnswerIsAnError() {
    respondWith("max_tokens", "{\"type\": \"text\", \"text\": \"Your spending in\"}");

    assertThatThrownBy(() -> insightsChat.ask("...", CALLER))
        .isInstanceOf(InsightsGenerationException.class)
        .extracting("stopReason")
        .isEqualTo("max_tokens");
  }

  private static void respondWith(String stopReason, String contentBlocks) {
    anthropic.stubFor(
        post(urlEqualTo("/v1/messages"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": "msg_test",
                          "type": "message",
                          "role": "assistant",
                          "model": "claude-opus-5-5",
                          "content": [%s],
                          "stop_reason": "%s",
                          "stop_sequence": null,
                          "usage": {"input_tokens": 10, "output_tokens": 5}
                        }
                        """
                            .formatted(contentBlocks, stopReason))));
  }
}
