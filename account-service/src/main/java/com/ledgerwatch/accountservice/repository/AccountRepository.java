package com.ledgerwatch.accountservice.repository;

import com.ledgerwatch.accountservice.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    
}
