package com.ledgerwatch.transactionservice.service;

import com.ledgerwatch.transactionservice.domain.Transaction;
import com.ledgerwatch.transactionservice.domain.TransactionStatus;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.dto.CreateTransactionRequest;
import com.ledgerwatch.transactionservice.dto.DashboardSummaryResponse;
import com.ledgerwatch.transactionservice.dto.DashboardSummaryResponse.BalancePoint;
import com.ledgerwatch.transactionservice.dto.DashboardSummaryResponse.TypeSpending;
import com.ledgerwatch.transactionservice.dto.UpdateTransactionRequest;
import com.ledgerwatch.transactionservice.repository.TransactionRepository;
import com.ledgerwatch.transactionservice.repository.TransactionSpecifications;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TransactionService {
  private final TransactionRepository repo;

  public TransactionService(TransactionRepository repo) {
    this.repo = repo;
  }

  public Transaction getById(UUID id) {
    Objects.requireNonNull(id, "Transaction id must not be null");
    return repo.findById(id)
        .orElseThrow(() -> new NoSuchElementException("Transaction not found: " + id));
  }

  public Page<Transaction> getAll(
      UUID accountId,
      TransactionType type,
      TransactionStatus status,
      String description,
      Instant createdFrom,
      Instant createdTo,
      Pageable pageable) {
    return repo.findAll(
        TransactionSpecifications.filter(
            accountId, type, status, description, createdFrom, createdTo),
        pageable);
  }

  public DashboardSummaryResponse getDashboardSummary(
      UUID accountId, Instant createdFrom, Instant createdTo) {
    List<Transaction> posted =
        repo.findAll(
            TransactionSpecifications.filter(
                accountId, null, TransactionStatus.POSTED, null, createdFrom, createdTo),
            Sort.by(Sort.Direction.ASC, "createdAt"));

    Map<LocalDate, BigDecimal> netByDay = new TreeMap<>();
    Map<TransactionType, BigDecimal> totalByType = new EnumMap<>(TransactionType.class);
    Map<TransactionType, Long> countByType = new EnumMap<>(TransactionType.class);

    for (Transaction tx : posted) {
      LocalDate day = tx.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
      BigDecimal signedAmount =
          tx.getType() == TransactionType.CREDIT ? tx.getAmount() : tx.getAmount().negate();
      netByDay.merge(day, signedAmount, BigDecimal::add);
      totalByType.merge(tx.getType(), tx.getAmount(), BigDecimal::add);
      countByType.merge(tx.getType(), 1L, Long::sum);
    }

    List<BalancePoint> balanceOverTime = new ArrayList<>();
    BigDecimal runningBalance = BigDecimal.ZERO;
    for (Map.Entry<LocalDate, BigDecimal> entry : netByDay.entrySet()) {
      runningBalance = runningBalance.add(entry.getValue());
      balanceOverTime.add(new BalancePoint(entry.getKey(), runningBalance));
    }

    List<TypeSpending> spendByType =
        totalByType.entrySet().stream()
            .map(e -> new TypeSpending(e.getKey(), e.getValue(), countByType.get(e.getKey())))
            .sorted(Comparator.comparing(TypeSpending::type))
            .toList();

    return new DashboardSummaryResponse(balanceOverTime, spendByType);
  }

  @Transactional
  public Transaction createTransaction(CreateTransactionRequest request) {
    Transaction tx = new Transaction();
    tx.setAccountId(request.accountId());
    tx.setType(request.type());
    tx.setAmount(request.amount());
    tx.setDescription(request.description());
    return repo.save(tx);
  }

  @Transactional
  public Transaction updateTransaction(UUID id, UpdateTransactionRequest request) {
    Transaction tx = Objects.requireNonNull(getById(id), "getById returned null");
    if (TransactionStatus.VOIDED.equals(tx.getStatus())) {
      throw new IllegalStateException("Voided transaction cannot be modified: " + id);
    }
    if (request.status() != null) {
      tx.setStatus(request.status());
    }
    if (request.description() != null) {
      tx.setDescription(request.description());
    }
    return Objects.requireNonNull(repo.save(tx), "Repository returned null for transaction");
  }
}
