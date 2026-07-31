package com.ledgerwatch.accountservice.service;

import com.ledgerwatch.accountservice.domain.Account;
import com.ledgerwatch.accountservice.dto.CreateAccountRequest;
import com.ledgerwatch.accountservice.dto.UpdateAccountRequest;
import com.ledgerwatch.accountservice.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AccountService {
    private final AccountRepository repo;

    public AccountService(AccountRepository repo) {
        this.repo = repo;
    }

    public Account getById(UUID id) {
        Objects.requireNonNull(id, "Account id must not be null");
        return repo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Account not found: " + id));
    }

    public List<Account> getAll() {
        return repo.findAll();
    }

    @Transactional
    public Account createAccount(CreateAccountRequest request) {
        Account account = new Account();
        account.setOwnerName(request.ownerName());
        if (request.initialBalance() != null) {
            if (request.initialBalance().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Initial balance cannot be negative");
            }
            account.setBalance(request.initialBalance());
        }
        return repo.save(account);
    }

    @Transactional
    public Account updateAccount(UUID id, UpdateAccountRequest request) {
        Account account = Objects.requireNonNull(getById(id), "getById returned null");
        if (request.ownerName() != null && !request.ownerName().isBlank()) {
            account.setOwnerName(request.ownerName());
        }
        if (request.status() != null) {
            account.setStatus(request.status());
        }
        return Objects.requireNonNull(repo.save(account), "Repository returned null for account");
    }
}
