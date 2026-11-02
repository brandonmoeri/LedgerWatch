package com.ledgerwatch.accountservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record EventReceipt(
    UUID eventId,
    @Schema(description = "True if the event had already been processed and nothing changed")
        boolean duplicate) {}
