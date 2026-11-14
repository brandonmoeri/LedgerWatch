package com.ledgerwatch.transactionservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/** The fields of account-service's account representation that transaction-service reads. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountSummary(UUID id, String status, Instant createdAt) {}
