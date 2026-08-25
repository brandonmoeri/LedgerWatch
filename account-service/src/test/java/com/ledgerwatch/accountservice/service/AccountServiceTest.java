package com.ledgerwatch.accountservice.service;

import com.ledgerwatch.accountservice.domain.Account;
import com.ledgerwatch.accountservice.domain.AccountStatus;
import com.ledgerwatch.accountservice.dto.CreateAccountRequest;
import com.ledgerwatch.accountservice.dto.UpdateAccountRequest;
import com.ledgerwatch.accountservice.repository.AccountRepository;
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
public class AccountServiceTest {
    
    @Mock
    AccountRepository accountRepository;

    @InjectMocks
    AccountService accountService;

    private Account existing;
    private UUID existingId;

    @BeforeEach
    void setUp() {
        existingId = UUID.randomUUID();
        existing = new Account();
        existing.setOwnerName("Alice");
    }

    // --- create ---

    @Test
    void create_persistsAccountWithOwnerName() {
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0, Account.class));

        Account result = accountService.createAccount(new CreateAccountRequest("Alice", null));

        assertThat(result.getOwnerName()).isEqualTo("Alice");
        assertThat(result.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(accountRepository).save(any(Account.class));
    }

    @Test
    void create_setsInitialBalanceWhenProvided() {
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Account result = accountService.createAccount(new CreateAccountRequest("Bob", new BigDecimal("500.00")));

        assertThat(result.getBalance()).isEqualByComparingTo("500.00");
    }

    @Test
    void create_rejectsNegativeInitialBalance() {
        assertThatThrownBy(() ->
            accountService.createAccount(new CreateAccountRequest("Charlie", new BigDecimal("-100.00")))
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("Initial balance cannot be negative");

        verifyNoInteractions(accountRepository);
    }

    // --- getById ---

    @Test
    void getById_returnsAccountWhenFound() {
        when(accountRepository.findById(existingId)).thenReturn(Optional.of(existing));

        Account result = accountService.getById(existingId);

        assertThat(result).isSameAs(existing);
    }

    @Test
    void getById_throwsWhenNotFound() {
        UUID unknown = UUID.randomUUID();
        when(accountRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getById(unknown))
            .isInstanceOf(NoSuchElementException.class)
            .hasMessageContaining(unknown.toString());
    }

    // --- getAll ---

    @Test
    void getAll_delegatesToRepositoryWithSpecificationAndPageable() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Account> page = new PageImpl<>(List.of(existing));
        when(accountRepository.findAll(ArgumentMatchers.<Specification<Account>>any(), eq(pageable)))
            .thenReturn(page);

        Page<Account> result = accountService.getAll(AccountStatus.ACTIVE, "Ali", pageable);

        assertThat(result.getContent()).containsExactly(existing);
        verify(accountRepository).findAll(ArgumentMatchers.<Specification<Account>>any(), eq(pageable));
    }

    // --- update ---

    @Test
    void update_patchesOwnerName() {
        when(accountRepository.findById(existingId)).thenReturn(Optional.of(existing));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Account result = accountService.updateAccount(existingId,
            new UpdateAccountRequest("Alice Renamed", null));

        assertThat(result.getOwnerName()).isEqualTo("Alice Renamed");
        assertThat(result.getStatus()).isEqualTo(AccountStatus.ACTIVE); // unchanged
    }

    @Test
    void update_patchesStatus() {
        when(accountRepository.findById(existingId)).thenReturn(Optional.of(existing));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Account result = accountService.updateAccount(existingId,
            new UpdateAccountRequest(null, AccountStatus.FROZEN));

        assertThat(result.getStatus()).isEqualTo(AccountStatus.FROZEN);
        assertThat(result.getOwnerName()).isEqualTo("Alice"); // unchanged
    }

    @Test
    void update_throwsWhenAccountNotFound() {
        UUID unknown = UUID.randomUUID();
        when(accountRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            accountService.updateAccount(unknown, new UpdateAccountRequest("X", null))
        ).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void update_ignoresBlankOwnerName() {
        when(accountRepository.findById(existingId)).thenReturn(Optional.of(existing));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Account result = accountService.updateAccount(existingId,
            new UpdateAccountRequest("   ", null));

        assertThat(result.getOwnerName()).isEqualTo("Alice"); // untouched
    }
}
