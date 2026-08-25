package com.ledgerwatch.transactionservice.service;

import com.ledgerwatch.transactionservice.domain.Transaction;
import com.ledgerwatch.transactionservice.domain.TransactionStatus;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.dto.CreateTransactionRequest;
import com.ledgerwatch.transactionservice.dto.UpdateTransactionRequest;
import com.ledgerwatch.transactionservice.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("null")
@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock TransactionRepository transactionRepository;
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
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var request = new CreateTransactionRequest(UUID.randomUUID(), TransactionType.DEBIT, new BigDecimal("50.00"), null);

        Transaction result = transactionService.createTransaction(request);

        assertThat(result.getType()).isEqualTo(TransactionType.DEBIT);
        assertThat(result.getAmount()).isEqualByComparingTo("50.00");
        assertThat(result.getStatus()).isEqualTo(TransactionStatus.POSTED);
        verify(transactionRepository).save(any());
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
        when(transactionRepository.findAll(ArgumentMatchers.<Specification<Transaction>>any(), eq(pageable)))
            .thenReturn(page);

        Page<Transaction> result = transactionService.getAll(
            existing.getAccountId(), TransactionType.CREDIT, TransactionStatus.POSTED, null, pageable);

        assertThat(result.getContent()).containsExactly(existing);
        verify(transactionRepository).findAll(ArgumentMatchers.<Specification<Transaction>>any(), eq(pageable));
    }

    @Test
    void update_patchesStatus() {
        when(transactionRepository.findById(existingId)).thenReturn(Optional.of(existing));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Transaction result = transactionService.updateTransaction(existingId, new UpdateTransactionRequest(TransactionStatus.VOIDED, null));

        assertThat(result.getStatus()).isEqualTo(TransactionStatus.VOIDED);
    }

    @Test
    void update_patchesDescription() {
        when(transactionRepository.findById(existingId)).thenReturn(Optional.of(existing));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Transaction result = transactionService.updateTransaction(existingId, new UpdateTransactionRequest(null, "corrected memo"));

        assertThat(result.getDescription()).isEqualTo("corrected memo");
        assertThat(result.getStatus()).isEqualTo(TransactionStatus.POSTED); // unchanged
    }

    @Test
    void update_throwsWhenNotFound() {
        UUID unknown = UUID.randomUUID();
        when(transactionRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.updateTransaction(unknown, new UpdateTransactionRequest(null, null)))
            .isInstanceOf(NoSuchElementException.class);
    }
}