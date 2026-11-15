package com.ledgerwatch.insightsservice.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ChatClientConfigTest {

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = "   ")
  void refusesToStartWithoutAnApiKey(String apiKey) {
    assertThatThrownBy(() -> ChatClientConfig.requireApiKey(apiKey))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ANTHROPIC_API_KEY");
  }

  @Test
  void acceptsAnApiKey() {
    assertThatCode(() -> ChatClientConfig.requireApiKey("sk-ant-test")).doesNotThrowAnyException();
  }
}
