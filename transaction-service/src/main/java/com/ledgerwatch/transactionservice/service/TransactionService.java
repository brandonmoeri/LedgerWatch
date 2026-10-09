package com.ledgerwatch.transactionservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerwatch.transactionservice.client.AccountServiceClient;
import com.ledgerwatch.transactionservice.domain.Transaction;
import com.ledgerwatch.transactionservice.domain.TransactionStatus;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.dto.CreateTransactionRequest;
import com.ledgerwatch.transactionservice.dto.CreateTransactionResult;
import com.ledgerwatch.transactionservice.dto.DashboardSummaryResponse;
import com.ledgerwatch.transactionservice.dto.DashboardSummaryResponse.BalancePoint;
import com.ledgerwatch.transactionservice.dto.DashboardSummaryResponse.TypeSpending;
import com.ledgerwatch.transactionservice.dto.TransactionResponse;
import com.ledgerwatch.transactionservice.dto.UpdateTransactionRequest;
import com.ledgerwatch.transactionservice.repository.IdempotencyRecordRepository;
import com.ledgerwatch.transactionservice.repository.TransactionRepository;
import com.ledgerwatch.transactionservice.repository.TransactionSpecifications;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
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
  static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

  private final TransactionRepository repo;
  private final IdempotencyRecordRepository idempotencyRepo;
  private final AccountServiceClient accountServiceClient;
  private final ObjectMapper objectMapper;

  public TransactionService(
      TransactionRepository repo,
      IdempotencyRecordRepository idempotencyRepo,
      AccountServiceClient accountServiceClient,
      ObjectMapper objectMapper) {
    this.repo = repo;
    this.idempotencyRepo = idempotencyRepo;
    this.accountServiceClient = accountServiceClient;
    this.objectMapper = objectMapper;
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

  /**
   * Inserts the transaction, then applies it to the account balance in account-service. The insert
   * is flushed first so local constraint failures surface before any remote side effect; if the
   * balance adjustment is rejected or fails, the exception rolls the insert back.
   */
  @Transactional
  public Transaction createTransaction(CreateTransactionRequest request) {
    Transaction saved = insert(request);
    applyToBalance(saved);
    return saved;
  }

  /**
   * Creates the transaction at most once per {@code (caller, idempotencyKey)}, the same way as
   * {@link #createTransaction(CreateTransactionRequest)}. If the key was already used for the same
   * request, this returns the stored original response. The key is claimed before anything else, so
   * a concurrent duplicate waits on the unique constraint and then replays. Only successes are
   * stored: a failure rolls the claim back with the insert, so the client can retry with the key. A
   * null key creates the transaction without idempotency.
   */
  @Transactional
  public CreateTransactionResult createTransaction(
      CreateTransactionRequest request, String caller, String idempotencyKey) {
    if (idempotencyKey == null) {
      return new CreateTransactionResult(
          TransactionResponse.from(createTransaction(request)), false);
    }
    if (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
      throw new IllegalArgumentException(
          "Idempotency-Key must be 1-" + MAX_IDEMPOTENCY_KEY_LENGTH + " non-blank characters");
    }
    Objects.requireNonNull(caller, "caller must not be null");

    String requestHash = requestHash(request);
    if (idempotencyRepo.claim(caller, idempotencyKey, requestHash) == 0) {
      return replay(caller, idempotencyKey, requestHash);
    }

    Transaction saved = insert(request);
    TransactionResponse response = TransactionResponse.from(saved);
    idempotencyRepo.complete(caller, idempotencyKey, saved.getId(), toJson(response));
    applyToBalance(saved);
    return new CreateTransactionResult(response, false);
  }

  private CreateTransactionResult replay(String caller, String idempotencyKey, String requestHash) {
    var stored =
        idempotencyRepo
            .findByCallerAndIdempotencyKey(caller, idempotencyKey)
            .orElseThrow(() -> new IllegalStateException("Idempotency record vanished"));
    if (!stored.getRequestHash().equals(requestHash)) {
      throw new IdempotencyKeyReusedException(idempotencyKey);
    }
    try {
      return new CreateTransactionResult(
          objectMapper.readValue(stored.getResponseBody(), TransactionResponse.class), true);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * SHA-256 over the request's fields, with the amount normalized so 10.5 and 10.50 match. The
   * description goes last with a marker so a null description and an empty one hash differently.
   */
  private static String requestHash(CreateTransactionRequest request) {
    String canonical =
        String.join(
            "|",
            String.valueOf(request.accountId()),
            String.valueOf(request.type()),
            request.amount().stripTrailingZeros().toPlainString(),
            request.description() == null ? "" : "=" + request.description());
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private String toJson(TransactionResponse response) {
    try {
      return objectMapper.writeValueAsString(response);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Transaction insert(CreateTransactionRequest request) {
    Transaction tx = new Transaction();
    tx.setAccountId(request.accountId());
    tx.setType(request.type());
    tx.setAmount(request.amount());
    tx.setDescription(request.description());
    return repo.saveAndFlush(tx);
  }

  private void applyToBalance(Transaction saved) {
    BigDecimal delta =
        saved.getType() == TransactionType.CREDIT ? saved.getAmount() : saved.getAmount().negate();
    accountServiceClient.applyBalanceDelta(saved.getAccountId(), delta);
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
