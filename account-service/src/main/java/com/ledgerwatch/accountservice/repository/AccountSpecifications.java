package com.ledgerwatch.accountservice.repository;

import com.ledgerwatch.accountservice.domain.Account;
import com.ledgerwatch.accountservice.domain.AccountStatus;
import org.springframework.data.jpa.domain.Specification;

public final class AccountSpecifications {

    private AccountSpecifications() {
    }

    public static Specification<Account> hasStatus(AccountStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    public static Specification<Account> ownerNameContains(String ownerName) {
        return (root, query, cb) -> (ownerName == null || ownerName.isBlank())
            ? null
            : cb.like(cb.lower(root.get("ownerName")), "%" + ownerName.toLowerCase() + "%");
    }

    public static Specification<Account> filter(AccountStatus status, String ownerName) {
        return Specification.where(hasStatus(status)).and(ownerNameContains(ownerName));
    }
}
