package com.ledgerwatch.insightsservice.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Claude asks for all three tools in one turn; checks each ledger call carries the asking user's
 * token, and that the results (but never the token) go back to Claude.
 */
@SpringBootTest
class LedgerToolsIntegrationTest {

  static final String ACCOUNT_ID = "6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b";
  static final String CALLER_TOKEN = "caller-token-abc";

  static final WireMockServer anthropic = new WireMockServer(wireMockConfig().dynamicPort());
  static final WireMockServer accountService = new WireMockServer(wireMockConfig().dynamicPort());
  static final WireMockServer transactionService =
      new WireMockServer(wireMockConfig().dynamicPort());

  static {
    anthropic.start();
    accountService.start();
    transactionService.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.ai.anthropic.base-url", anthropic::baseUrl);
    registry.add("spring.ai.anthropic.api-key", () -> "test-key");
    registry.add("spring.ai.retry.max-attempts", () -> "1");
    registry.add("jwt.secret", () -> "test-secret-that-is-at-least-32-bytes-long");
    registry.add("ledger-services.account-service-url", accountService::baseUrl);
    registry.add("ledger-services.transaction-service-url", transactionService::baseUrl);
  }

  @AfterAll
  static void stop() {
    anthropic.stop();
    accountService.stop();
    transactionService.stop();
  }

  @Autowired InsightsChat insightsChat;

  @BeforeEach
  void reset() {
    anthropic.resetAll();
    accountService.resetAll();
    transactionService.resetAll();
  }

  @Test
  void eachToolCallsTheLedgerAsTheCallerAndReturnsTheDataToClaude() {
    accountService.stubFor(
        get(urlEqualTo("/accounts/" + ACCOUNT_ID))
            .willReturn(
                okJson(
                    """
                    {"id": "%s", "ownerName": "Alex Doe", "status": "ACTIVE",
                     "balance": 1234.56, "createdAt": "2026-01-01T00:00:00Z",
                     "updatedAt": "2026-11-01T00:00:00Z"}
                    """
                        .formatted(ACCOUNT_ID))));
    transactionService.stubFor(
        get(urlPathEqualTo("/transactions"))
            .willReturn(
                okJson(
                    """
                    {"content": [{"id": "11111111-1111-4111-8111-111111111111",
                      "accountId": "%s", "type": "DEBIT", "status": "POSTED",
                      "amount": 42.10, "description": "Corner grocer",
                      "createdAt": "2026-11-10T12:00:00Z", "flags": []}],
                     "page": {"size": 20, "number": 0, "totalElements": 1, "totalPages": 1}}
                    """
                        .formatted(ACCOUNT_ID))));
    transactionService.stubFor(
        get(urlPathEqualTo("/accounts/" + ACCOUNT_ID + "/flags"))
            .willReturn(
                okJson(
                    """
                    {"content": [{"id": "22222222-2222-4222-8222-222222222222",
                      "transactionId": "11111111-1111-4111-8111-111111111111",
                      "accountId": "%s", "rule": "VELOCITY", "detail": "6 debits in 10m",
                      "blocked": false, "flaggedAt": "2026-11-10T12:00:01Z"}],
                     "page": {"size": 20, "number": 0, "totalElements": 1, "totalPages": 1}}
                    """
                        .formatted(ACCOUNT_ID))));

    // Second turn: Claude has the tool results and answers.
    anthropic.stubFor(
        post(urlEqualTo("/v1/messages"))
            .atPriority(1)
            .withRequestBody(containing("\"tool_result\""))
            .willReturn(message("end_turn", "{\"type\": \"text\", \"text\": \"All good.\"}")));
    // First turn: Claude calls all three tools.
    anthropic.stubFor(
        post(urlEqualTo("/v1/messages"))
            .atPriority(5)
            .willReturn(
                message(
                    "tool_use",
                    """
                    {"type": "tool_use", "id": "toolu_1", "name": "getAccountSummary",
                     "input": {"accountId": "%1$s"}},
                    {"type": "tool_use", "id": "toolu_2", "name": "searchTransactions",
                     "input": {"accountId": "%1$s", "type": "DEBIT",
                               "createdFrom": "2026-11-01T00:00:00Z"}},
                    {"type": "tool_use", "id": "toolu_3", "name": "getFlags",
                     "input": {"accountId": "%1$s"}}
                    """
                        .formatted(ACCOUNT_ID))));

    Jwt caller = Jwt.withTokenValue(CALLER_TOKEN).header("alg", "HS256").subject("analyst").build();

    assertThat(insightsChat.ask("Anything odd on my account?", caller)).isEqualTo("All good.");

    String bearer = "Bearer " + CALLER_TOKEN;
    accountService.verify(
        getRequestedFor(urlEqualTo("/accounts/" + ACCOUNT_ID))
            .withHeader("Authorization", equalTo(bearer)));
    transactionService.verify(
        getRequestedFor(urlPathEqualTo("/transactions"))
            .withHeader("Authorization", equalTo(bearer))
            .withQueryParam("accountId", equalTo(ACCOUNT_ID))
            .withQueryParam("type", equalTo("DEBIT"))
            .withQueryParam("createdFrom", equalTo("2026-11-01T00:00:00Z")));
    transactionService.verify(
        getRequestedFor(urlPathEqualTo("/accounts/" + ACCOUNT_ID + "/flags"))
            .withHeader("Authorization", equalTo(bearer)));

    anthropic.verify(
        postRequestedFor(urlEqualTo("/v1/messages"))
            .withRequestBody(containing("\"tool_result\""))
            .withRequestBody(containing("Alex Doe"))
            .withRequestBody(containing("Corner grocer"))
            .withRequestBody(containing("VELOCITY")));
    // The token reaches the ledger services only, never Claude.
    anthropic.verify(
        2,
        postRequestedFor(urlEqualTo("/v1/messages")).withRequestBody(notContaining(CALLER_TOKEN)));
  }

  @Test
  void aLedgerRefusalReachesClaudeAsAToolResultWithoutLeakingTheBody() {
    accountService.stubFor(
        get(urlEqualTo("/accounts/" + ACCOUNT_ID))
            .willReturn(
                aResponse()
                    .withStatus(403)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody("{\"detail\": \"internal-detail-xyz\"}")));
    anthropic.stubFor(
        post(urlEqualTo("/v1/messages"))
            .atPriority(1)
            .withRequestBody(containing("\"tool_result\""))
            .willReturn(
                message("end_turn", "{\"type\": \"text\", \"text\": \"You can't see that.\"}")));
    anthropic.stubFor(
        post(urlEqualTo("/v1/messages"))
            .atPriority(5)
            .willReturn(
                message(
                    "tool_use",
                    """
                    {"type": "tool_use", "id": "toolu_1", "name": "getAccountSummary",
                     "input": {"accountId": "%s"}}
                    """
                        .formatted(ACCOUNT_ID))));

    Jwt caller = Jwt.withTokenValue(CALLER_TOKEN).header("alg", "HS256").subject("analyst").build();

    assertThat(insightsChat.ask("What's my balance?", caller)).isEqualTo("You can't see that.");
    anthropic.verify(
        postRequestedFor(urlEqualTo("/v1/messages"))
            .withRequestBody(containing("not allowed to read this data"))
            .withRequestBody(notContaining("internal-detail-xyz")));
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder message(
      String stopReason, String contentBlocks) {
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
