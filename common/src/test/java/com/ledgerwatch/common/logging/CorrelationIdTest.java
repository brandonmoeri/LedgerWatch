package com.ledgerwatch.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

class CorrelationIdTest {

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc", "3f6c1a2e-7d1b-4c3e-9a55-0b2d7e8f9a10", "trace:1.2_x-y"})
  void sanitize_keepsValidIds(String id) {
    assertThat(CorrelationId.sanitize(id)).isEqualTo(id);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"has space", "line\nbreak", "{\"json\":1}", "<script>"})
  void sanitize_replacesUnusableIds(String id) {
    String sanitized = CorrelationId.sanitize(id);
    assertThat(sanitized).isNotEqualTo(id);
    assertThat(CorrelationId.isValid(sanitized)).isTrue();
  }

  @Test
  void sanitize_replacesOverlongIds() {
    String max = "a".repeat(CorrelationId.MAX_LENGTH);
    assertThat(CorrelationId.sanitize(max)).isEqualTo(max);
    assertThat(CorrelationId.sanitize(max + "a")).isNotEqualTo(max + "a");
  }

  @Test
  void open_setsMdc_andCloseRemovesIt() {
    try (CorrelationId.Scope scope = CorrelationId.open("outer")) {
      assertThat(CorrelationId.current()).isEqualTo("outer");
      assertThat(MDC.get(CorrelationId.MDC_KEY)).isEqualTo("outer");
    }
    assertThat(CorrelationId.current()).isNull();
  }

  @Test
  void nestedScope_restoresTheOuterId() {
    try (CorrelationId.Scope outer = CorrelationId.open("outer")) {
      try (CorrelationId.Scope inner = CorrelationId.open("inner")) {
        assertThat(CorrelationId.current()).isEqualTo("inner");
      }
      assertThat(CorrelationId.current()).isEqualTo("outer");
    }
  }

  @Test
  void open_withNull_generatesAnId() {
    try (CorrelationId.Scope scope = CorrelationId.open(null)) {
      assertThat(CorrelationId.isValid(CorrelationId.current())).isTrue();
    }
  }
}
