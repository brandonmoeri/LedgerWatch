package com.ledgerwatch.insightsservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /insights/ask}. The length cap bounds what one request can spend. */
public record AskRequest(
    @NotBlank(message = "question is required")
        @Size(max = AskRequest.MAX_QUESTION_LENGTH, message = "question is too long")
        String question) {

  public static final int MAX_QUESTION_LENGTH = 2000;
}
