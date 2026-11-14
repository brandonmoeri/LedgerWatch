package com.ledgerwatch.transactionservice.controller;

import com.ledgerwatch.transactionservice.domain.FraudFlag;
import com.ledgerwatch.transactionservice.domain.Transaction;
import com.ledgerwatch.transactionservice.dto.TransactionResponse;
import com.ledgerwatch.transactionservice.repository.FraudFlagRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/** Builds {@link TransactionResponse}s with their fraud flags, one flag query per call. */
@Component
class TransactionResponses {

  private final FraudFlagRepository flags;

  TransactionResponses(FraudFlagRepository flags) {
    this.flags = flags;
  }

  TransactionResponse of(Transaction t) {
    return TransactionResponse.from(t, flags.findByTransactionIdInOrderByRule(List.of(t.getId())));
  }

  Page<TransactionResponse> of(Page<Transaction> page) {
    List<UUID> ids = page.map(Transaction::getId).getContent();
    Map<UUID, List<FraudFlag>> byTransaction =
        ids.isEmpty()
            ? Map.of()
            : flags.findByTransactionIdInOrderByRule(ids).stream()
                .collect(Collectors.groupingBy(FraudFlag::getTransactionId));
    return page.map(
        t -> TransactionResponse.from(t, byTransaction.getOrDefault(t.getId(), List.of())));
  }
}
