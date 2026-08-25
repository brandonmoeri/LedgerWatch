package com.ledgerwatch.transactionservice.repository;

import com.ledgerwatch.transactionservice.domain.Transaction;
import com.ledgerwatch.transactionservice.domain.TransactionStatus;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

public final class TransactionSpecifications {

    private TransactionSpecifications() {
    }

    public static Specification<Transaction> hasAccountId(UUID accountId) {
        return (root, query, cb) -> accountId == null ? null : cb.equal(root.get("accountId"), accountId);
    }

    public static Specification<Transaction> hasType(TransactionType type) {
        return (root, query, cb) -> type == null ? null : cb.equal(root.get("type"), type);
    }

    public static Specification<Transaction> hasStatus(TransactionStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    public static Specification<Transaction> descriptionContains(String description) {
        return (root, query, cb) -> (description == null || description.isBlank())
            ? null
            : cb.like(cb.lower(root.get("description")), "%" + description.toLowerCase() + "%");
    }

    public static Specification<Transaction> filter(UUID accountId, TransactionType type, TransactionStatus status, String description) {
        return Specification.where(hasAccountId(accountId))
            .and(hasType(type))
            .and(hasStatus(status))
            .and(descriptionContains(description));
    }
}
