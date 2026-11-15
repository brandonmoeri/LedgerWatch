package com.ledgerwatch.insightsservice.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** POST /insights/ask end to end, with WireMock standing in for Anthropic and account-service. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class InsightsControllerIntegrationTest {

  static final String ACCOUNT_ID = "6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b";

  static final WireMockServer anthropic = new WireMockServer(wireMockConfig().dynamicPort());
  static final WireMockServer accountService = new WireMockServer(wireMockConfig().dynamicPort());

  static {
    anthropic.start();
    accountService.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.ai.anthropic.base-url", anthropic::baseUrl);
    registry.add("spring.ai.anthropic.api-key", () -> "test-key");
    registry.add("spring.ai.retry.max-attempts", () -> "1");
    registry.add("jwt.secret", () -> "test-secret-that-is-at-least-32-bytes-long");
    registry.add("ledger-services.account-service-url", accountService::baseUrl);
  }

  @AfterAll
  static void stop() {
    anthropic.stop();
    accountService.stop();
  }

  @Autowired MockMvc mvc;

  @BeforeEach
  void reset() {
    anthropic.resetAll();
    accountService.resetAll();
  }

  @Test
  void answersAndLogsTokensSummedOverTheToolLoopAndEachToolCall(CapturedOutput output)
      throws Exception {
    accountService.stubFor(
        get(urlEqualTo("/accounts/" + ACCOUNT_ID))
            .willReturn(
                okJson(
                    """
                    {"id": "%s", "ownerName": "Alex Doe", "status": "ACTIVE",
                     "balance": 1234.56}
                    """
                        .formatted(ACCOUNT_ID))));
    stubToolRound("getAccountSummary");

    mvc.perform(ask("What's my balance?"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").value("Your balance is 1234.56."));

    // Two model calls at 10 in / 5 out each.
    assertThat(output)
        .contains("Insights request outcome=answered")
        .contains("model=claude-opus-5-5")
        .contains("stopReason=end_turn")
        .contains("inputTokens=20")
        .contains("outputTokens=10")
        .contains("totalTokens=30")
        .contains("toolCallCount=1")
        .containsPattern("toolCalls=\\[getAccountSummary\\(\\d+ms\\)\\]")
        .doesNotContain("What's my balance?");
  }

  @Test
  void aToolThatFailsIsLoggedAsFailed(CapturedOutput output) throws Exception {
    accountService.stubFor(
        get(urlEqualTo("/accounts/" + ACCOUNT_ID)).willReturn(aResponse().withStatus(403)));
    stubToolRound("getAccountSummary");

    mvc.perform(ask("What's my balance?")).andExpect(status().isOk());

    assertThat(output).containsPattern("toolCalls=\\[getAccountSummary\\(\\d+ms, failed\\)\\]");
  }

  @Test
  void refusalIs422AndLogged(CapturedOutput output) throws Exception {
    anthropic.stubFor(post(urlEqualTo("/v1/messages")).willReturn(message("refusal", "")));

    mvc.perform(ask("..."))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.title").value("Question declined"));

    assertThat(output)
        .contains("WARN")
        .contains("Insights request outcome=refusal")
        .contains("inputTokens=10")
        .contains("toolCallCount=0");
  }

  @Test
  void anthropicOutageIs503AndLoggedWithoutTokens(CapturedOutput output) throws Exception {
    anthropic.stubFor(
        post(urlEqualTo("/v1/messages"))
            .willReturn(aResponse().withStatus(529).withBody("secret-upstream-detail")));

    mvc.perform(ask("Hello"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("secret-upstream-detail"))));

    assertThat(output)
        .contains("Insights request outcome=error")
        .contains("inputTokens=null");
  }

  @Test
  void blankQuestionIs400WithoutCallingClaude() throws Exception {
    mvc.perform(ask("  ")).andExpect(status().isBadRequest());
    mvc.perform(ask("x".repeat(2001))).andExpect(status().isBadRequest());

    assertThat(anthropic.getAllServeEvents()).isEmpty();
  }

  @Test
  void requiresABearerToken() throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.post("/insights/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"Hi\"}"))
        .andExpect(status().isUnauthorized());
  }

  private static MockHttpServletRequestBuilder ask(String question) {
    return MockMvcRequestBuilders.post("/insights/ask")
        .with(jwt().jwt(j -> j.tokenValue("caller-token").subject("analyst")))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"question\": \"%s\"}".formatted(question));
  }

  /** First call: Claude asks for {@code tool}; second (with the tool result): it answers. */
  private static void stubToolRound(String tool) {
    anthropic.stubFor(
        post(urlEqualTo("/v1/messages"))
            .atPriority(1)
            .withRequestBody(containing("\"tool_result\""))
            .willReturn(
                message(
                    "end_turn", "{\"type\": \"text\", \"text\": \"Your balance is 1234.56.\"}")));
    anthropic.stubFor(
        post(urlEqualTo("/v1/messages"))
            .atPriority(5)
            .willReturn(
                message(
                    "tool_use",
                    """
                    {"type": "tool_use", "id": "toolu_1", "name": "%s",
                     "input": {"accountId": "%s"}}
                    """
                        .formatted(tool, ACCOUNT_ID))));
  }

  private static ResponseDefinitionBuilder message(String stopReason, String contentBlocks) {
    return aResponse()
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
                .formatted(contentBlocks, stopReason));
  }
}
