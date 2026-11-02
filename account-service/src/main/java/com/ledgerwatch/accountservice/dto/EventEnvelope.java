package com.ledgerwatch.accountservice.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** An event delivered by another service's outbox publisher. */
public record EventEnvelope(
    @Schema(description = "The producer's event id; each id is processed at most once.")
        @NotNull(message = "eventId is required")
        UUID eventId,
    @Schema(description = "Event type, e.g. TransactionCreated", example = "TransactionCreated")
        @NotBlank(message = "eventType is required")
        String eventType,
    @Schema(description = "Event body; its shape depends on eventType")
        @NotNull(message = "payload is required")
        JsonNode payload) {}
