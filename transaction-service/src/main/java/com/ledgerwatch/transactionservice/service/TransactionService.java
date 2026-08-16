package com.ledgerwatch.transactionservice.service;

import com.ledgerwatch.transactionservice.domain.Transaction;
import com.ledgerwatch.transactionservice.domain.TransactionStatus;
import com.ledgerwatch.transactionservice.dto.CreateTransactionRequest;
import com.ledgerwatch.transactionservice.dto.UpdateTransactionRequest;
import com.ledgerwatch.transactionservice.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

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

    public List<Transaction> getAll() {
        return repo.findAll();
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
