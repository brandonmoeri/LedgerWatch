package com.ledgerwatch.transactionservice.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerwatch.transactionservice.client.AccountServiceClient;
import com.ledgerwatch.transactionservice.domain.IdempotencyRecord;
import com.ledgerwatch.transactionservice.domain.Transaction;
import com.ledgerwatch.transactionservice.domain.TransactionStatus;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.dto.CreateTransactionRequest;
import com.ledgerwatch.transactionservice.dto.CreateTransactionResult;
import com.ledgerwatch.transactionservice.dto.DashboardSummaryResponse;
import com.ledgerwatch.transactionservice.dto.TransactionResponse;
import com.ledgerwatch.transactionservice.dto.UpdateTransactionRequest;
import com.ledgerwatch.transactionservice.repository.IdempotencyRecordRepository;
import com.ledgerwatch.transactionservice.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

@SuppressWarnings("null")
@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

  @Mock TransactionRepository transactionRepository;
  @Mock IdempotencyRecordRepository idempotencyRecordRepository;
  @Mock AccountServiceClient accountServiceClient;
  @Spy ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  @InjectMocks TransactionService transactionService;

  private UUID existingId;
  private Transaction existing;

  @BeforeEach
  void setUp() {
    existingId = UUID.randomUUID();
    existing = new Transaction();
    existing.setAccountId(UUID.randomUUID());
    existing.setType(TransactionType.CREDIT);
    existing.setAmount(new BigDecimal("100.00"));
  }

  @Test
  void create_persistsTransactionWithRequiredFields() {
    when(transactionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    var request =
        new CreateTransactionRequest(
            UUID.randomUUID(), TransactionType.DEBIT, new BigDecimal("50.00"), null);

    Transaction result = transactionService.createTransaction(request);

    assertThat(result.getType()).isEqualTo(TransactionType.DEBIT);
    assertThat(result.getAmount()).isEqualByComparingTo("50.00");
    assertThat(result.getStatus()).isEqualTo(TransactionStatus.POSTED);
    verify(transactionRepository).saveAndFlush(any());
  }

  @Test
  void create_debit_appliesNegativeDeltaAfterInsert() {
    when(transactionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    UUID accountId = UUID.randomUUID();

    transactionService.createTransaction(
        new CreateTransactionRequest(
            accountId, TransactionType.DEBIT, new BigDecimal("50.00"), null));

    InOrder inOrder = inOrder(transactionRepository, accountServiceClient);
    inOrder.verify(transactionRepository).saveAndFlush(any());
    inOrder.verify(accountServiceClient).applyBalanceDelta(accountId, new BigDecimal("-50.00"));
  }

  @Test
  void create_credit_appliesPositiveDelta() {
    when(transactionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    UUID accountId = UUID.randomUUID();

    transactionService.createTransaction(
        new CreateTransactionRequest(
            accountId, TransactionType.CREDIT, new BigDecimal("75.25"), null));

    verify(accountServiceClient).applyBalanceDelta(accountId, new BigDecimal("75.25"));
  }

  @Test
  void create_accountServiceRejects_propagatesException() {
    when(transactionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    doThrow(new IllegalStateException("frozen"))
        .when(accountServiceClient)
        .applyBalanceDelta(any(), any());

    assertThatThrownBy(
            () ->
                transactionService.createTransaction(
                    new CreateTransactionRequest(
                        UUID.randomUUID(), TransactionType.DEBIT, BigDecimal.TEN, null)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void createIdempotent_nullKey_createsWithoutClaiming() {
    when(transactionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    CreateTransactionResult result =
        transactionService.createTransaction(
            new CreateTransactionRequest(
                UUID.randomUUID(), TransactionType.CREDIT, BigDecimal.TEN, null),
            "admin",
            null);

    assertThat(result.replayed()).isFalse();
    verifyNoInteractions(idempotencyRecordRepository);
    verify(accountServiceClient).applyBalanceDelta(any(), any());
  }

  @Test
  void createIdempotent_newKey_claimsAndStoresResponseBeforeCallingAccountService() {
    when(transactionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    when(idempotencyRecordRepository.claim(eq("admin"), eq("key-1"), any())).thenReturn(1);
    UUID accountId = UUID.randomUUID();

    CreateTransactionResult result =
        transactionService.createTransaction(
            new CreateTransactionRequest(
                accountId, TransactionType.DEBIT, new BigDecimal("5.00"), "coffee"),
            "admin",
            "key-1");

    assertThat(result.replayed()).isFalse();
    assertThat(result.response().description()).isEqualTo("coffee");
    InOrder inOrder =
        inOrder(idempotencyRecordRepository, transactionRepository, accountServiceClient);
    inOrder.verify(idempotencyRecordRepository).claim(eq("admin"), eq("key-1"), any());
    inOrder.verify(transactionRepository).saveAndFlush(any());
    inOrder
        .verify(idempotencyRecordRepository)
        .complete(eq("admin"), eq("key-1"), any(), contains("\"coffee\""));
    inOrder.verify(accountServiceClient).applyBalanceDelta(accountId, new BigDecimal("-5.00"));
  }

  @Test
  void createIdempotent_claimedKeySamePayload_replaysStoredResponseWithoutSideEffects()
      throws Exception {
    var request =
        new CreateTransactionRequest(
            UUID.randomUUID(), TransactionType.CREDIT, new BigDecimal("10.50"), null);
    var original =
        new TransactionResponse(
            UUID.randomUUID(),
            request.accountId(),
            TransactionType.CREDIT,
            TransactionStatus.POSTED,
            new BigDecimal("10.50"),
            null,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"));
    ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
    when(idempotencyRecordRepository.claim(eq("admin"), eq("key-1"), hash.capture())).thenReturn(0);
    String originalJson = objectMapper.writeValueAsString(original);
    IdempotencyRecord stored = mock(IdempotencyRecord.class);
    when(stored.getRequestHash()).thenAnswer(inv -> hash.getValue());
    when(stored.getResponseBody()).thenReturn(originalJson);
    when(idempotencyRecordRepository.findByCallerAndIdempotencyKey("admin", "key-1"))
        .thenReturn(Optional.of(stored));

    // Same amount at a different scale still counts as the same request.
    var sameRequestRescaled =
        new CreateTransactionRequest(
            request.accountId(), TransactionType.CREDIT, new BigDecimal("10.5"), null);
    CreateTransactionResult result =
        transactionService.createTransaction(sameRequestRescaled, "admin", "key-1");

    assertThat(result.replayed()).isTrue();
    assertThat(result.response()).isEqualTo(original);
    verify(transactionRepository, never()).saveAndFlush(any());
    verifyNoInteractions(accountServiceClient);
  }

  @Test
  void createIdempotent_claimedKeyDifferentPayload_throwsKeyReused() {
    when(idempotencyRecordRepository.claim(eq("admin"), eq("key-1"), any())).thenReturn(0);
    IdempotencyRecord stored = mock(IdempotencyRecord.class);
    when(stored.getRequestHash()).thenReturn("hash-of-some-other-request");
    when(idempotencyRecordRepository.findByCallerAndIdempotencyKey("admin", "key-1"))
        .thenReturn(Optional.of(stored));

    assertThatThrownBy(
            () ->
                transactionService.createTransaction(
                    new CreateTransactionRequest(
                        UUID.randomUUID(), TransactionType.CREDIT, BigDecimal.ONE, null),
                    "admin",
                    "key-1"))
        .isInstanceOf(IdempotencyKeyReusedException.class);
    verify(transactionRepository, never()).saveAndFlush(any());
    verifyNoInteractions(accountServiceClient);
  }

  @Test
  void createIdempotent_blankOrOverlongKey_throwsIllegalArgument() {
    var request =
        new CreateTransactionRequest(
            UUID.randomUUID(), TransactionType.CREDIT, BigDecimal.ONE, null);

    assertThatThrownBy(() -> transactionService.createTransaction(request, "admin", "  "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                transactionService.createTransaction(
                    request,
                    "admin",
                    "k".repeat(TransactionService.MAX_IDEMPOTENCY_KEY_LENGTH + 1)))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(idempotencyRecordRepository, accountServiceClient);
  }

  @Test
  void getById_returnsTransactionWhenFound() {
    when(transactionRepository.findById(existingId)).thenReturn(Optional.of(existing));
    assertThat(transactionService.getById(existingId)).isSameAs(existing);
  }

  @Test
  void getById_throwsWhenNotFound() {
    UUID unknown = UUID.randomUUID();
    when(transactionRepository.findById(unknown)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> transactionService.getById(unknown))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessageContaining(unknown.toString());
  }

  @Test
  void getAll_delegatesToRepositoryWithSpecificationAndPageable() {
    Pageable pageable = PageRequest.of(0, 10);
    Page<Transaction> page = new PageImpl<>(List.of(existing));
    when(transactionRepository.findAll(
            ArgumentMatchers.<Specification<Transaction>>any(), eq(pageable)))
        .thenReturn(page);

    Page<Transaction> result =
        transactionService.getAll(
            existing.getAccountId(),
            TransactionType.CREDIT,
            TransactionStatus.POSTED,
            null,
            null,
            null,
            pageable);

    assertThat(result.getContent()).containsExactly(existing);
    verify(transactionRepository)
        .findAll(ArgumentMatchers.<Specification<Transaction>>any(), eq(pageable));
  }

  @Test
  void update_patchesStatus() {
    when(transactionRepository.findById(existingId)).thenReturn(Optional.of(existing));
    when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Transaction result =
        transactionService.updateTransaction(
            existingId, new UpdateTransactionRequest(TransactionStatus.VOIDED, null));

    assertThat(result.getStatus()).isEqualTo(TransactionStatus.VOIDED);
  }

  @Test
  void update_patchesDescription() {
    when(transactionRepository.findById(existingId)).thenReturn(Optional.of(existing));
    when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Transaction result =
        transactionService.updateTransaction(
            existingId, new UpdateTransactionRequest(null, "corrected memo"));

    assertThat(result.getDescription()).isEqualTo("corrected memo");
    assertThat(result.getStatus()).isEqualTo(TransactionStatus.POSTED); // unchanged
  }

  @Test
  void getDashboardSummary_computesCumulativeBalanceAndTotalsByType() {
    UUID accountId = existing.getAccountId();
    Instant day1 = Instant.parse("2026-01-01T10:00:00Z");
    Instant day2 = day1.plus(1, ChronoUnit.DAYS);

    Transaction credit = new Transaction();
    credit.setAccountId(accountId);
    credit.setType(TransactionType.CREDIT);
    credit.setStatus(TransactionStatus.POSTED);
    credit.setAmount(new BigDecimal("100.00"));
    setCreatedAt(credit, day1);

    Transaction debit = new Transaction();
    debit.setAccountId(accountId);
    debit.setType(TransactionType.DEBIT);
    debit.setStatus(TransactionStatus.POSTED);
    debit.setAmount(new BigDecimal("30.00"));
    setCreatedAt(debit, day2);

    when(transactionRepository.findAll(
            ArgumentMatchers.<Specification<Transaction>>any(), any(Sort.class)))
        .thenReturn(List.of(credit, debit));

    DashboardSummaryResponse summary =
        transactionService.getDashboardSummary(accountId, null, null);

    assertThat(summary.balanceOverTime()).hasSize(2);
    assertThat(summary.balanceOverTime().get(0).balance()).isEqualByComparingTo("100.00");
    assertThat(summary.balanceOverTime().get(1).balance()).isEqualByComparingTo("70.00");

    assertThat(summary.spendByType()).hasSize(2);
    var creditSummary =
        summary.spendByType().stream().filter(s -> s.type() == TransactionType.CREDIT).findFirst();
    assertThat(creditSummary).isPresent();
    assertThat(creditSummary.get().total()).isEqualByComparingTo("100.00");
    assertThat(creditSummary.get().count()).isEqualTo(1);
  }

  private static void setCreatedAt(Transaction tx, Instant createdAt) {
    try {
      var field = Transaction.class.getDeclaredField("createdAt");
      field.setAccessible(true);
      field.set(tx, createdAt);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void update_throwsWhenNotFound() {
    UUID unknown = UUID.randomUUID();
    when(transactionRepository.findById(unknown)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                transactionService.updateTransaction(
                    unknown, new UpdateTransactionRequest(null, null)))
        .isInstanceOf(NoSuchElementException.class);
  }
}
