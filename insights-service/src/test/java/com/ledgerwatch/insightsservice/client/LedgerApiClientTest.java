package com.ledgerwatch.insightsservice.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.matching.AbsentPattern;
import com.ledgerwatch.insightsservice.ai.LedgerTools;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.web.client.RestClient;

class LedgerApiClientTest {

  static final WireMockServer ledger = new WireMockServer(wireMockConfig().dynamicPort());
  static final UUID ACCOUNT = UUID.fromString("6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b");
  static final String EMPTY_PAGE =
      "{\"content\": [], \"page\": {\"size\": 20, \"number\": 0, \"totalElements\": 0,"
          + " \"totalPages\": 0}}";

  static {
    ledger.start();
  }

  @AfterAll
  static void stop() {
    ledger.stop();
  }

  LedgerApiClient client;

  @BeforeEach
  void setUp() {
    ledger.resetAll();
    URI url = URI.create(ledger.baseUrl());
    client =
        new LedgerApiClient(
            RestClient.builder(),
            new LedgerServicesProperties(url, url, Duration.ofSeconds(1), Duration.ofSeconds(2)));
  }

  @Test
  void descriptionCannotSmuggleInExtraQueryParameters() {
    ledger.stubFor(get(urlPathEqualTo("/transactions")).willReturn(okJson(EMPTY_PAGE)));
    UUID other = UUID.randomUUID();

    client.searchTransactions(
        ACCOUNT, null, null, "x&accountId=" + other, null, null, null, null, "t");

    ledger.verify(
        getRequestedFor(urlPathEqualTo("/transactions"))
            .withQueryParam("accountId", equalTo(ACCOUNT.toString()))
            .withQueryParam("description", equalTo("x&accountId=" + other))
            .withQueryParam("type", AbsentPattern.ABSENT));
  }

  @Test
  void pageSizeIsCapped() {
    ledger.stubFor(get(urlPathEqualTo("/transactions")).willReturn(okJson(EMPTY_PAGE)));

    client.searchTransactions(ACCOUNT, null, null, null, null, null, -3, 10_000, "t");

    ledger.verify(
        getRequestedFor(urlPathEqualTo("/transactions"))
            .withQueryParam("page", equalTo("0"))
            .withQueryParam("size", equalTo(String.valueOf(LedgerApiClient.MAX_PAGE_SIZE))));
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403})
  void refusalIsReportedWithoutTheResponseBody(int status) {
    ledger.stubFor(
        get(urlPathEqualTo("/accounts/" + ACCOUNT))
            .willReturn(aResponse().withStatus(status).withBody("secret-detail")));

    assertThatThrownBy(() -> client.getAccount(ACCOUNT, "t"))
        .isInstanceOf(LedgerServiceException.class)
        .hasMessageContaining("not allowed")
        .hasMessageNotContaining("secret-detail");
  }

  @Test
  void unknownAccountIsNotFound() {
    ledger.stubFor(
        get(urlPathEqualTo("/accounts/" + ACCOUNT)).willReturn(aResponse().withStatus(404)));

    assertThatThrownBy(() -> client.getAccount(ACCOUNT, "t"))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void serverErrorIsReported() {
    ledger.stubFor(
        get(urlPathEqualTo("/accounts/" + ACCOUNT + "/flags"))
            .willReturn(aResponse().withStatus(503)));

    assertThatThrownBy(() -> client.getFlags(ACCOUNT, null, null, "t"))
        .isInstanceOf(LedgerServiceException.class)
        .hasMessage("transaction-service returned 503");
  }

  @Test
  void noTokenMeansNoRequest() {
    assertThatThrownBy(() -> client.getAccount(ACCOUNT, " "))
        .isInstanceOf(IllegalStateException.class);
    ledger.verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  void toolsRefuseAToolContextWithoutTheCallerToken() {
    LedgerTools tools = new LedgerTools(client);

    assertThatThrownBy(() -> tools.getAccountSummary(ACCOUNT, new ToolContext(Map.of())))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> tools.getFlags(ACCOUNT, null, null, null))
        .isInstanceOf(IllegalStateException.class);
    ledger.verify(0, anyRequestedFor(anyUrl()));
  }
}
