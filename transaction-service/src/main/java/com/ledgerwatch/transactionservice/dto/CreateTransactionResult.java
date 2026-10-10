package com.ledgerwatch.transactionservice.dto;

/** Outcome of an idempotent create: the response body and whether it is a stored replay. */
public record CreateTransactionResult(TransactionResponse response, boolean replayed) {}
