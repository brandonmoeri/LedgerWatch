package com.ledgerwatch.accountservice.controller;

import com.ledgerwatch.accountservice.dto.AccountResponse;
import com.ledgerwatch.accountservice.dto.CreateAccountRequest;
import com.ledgerwatch.accountservice.dto.UpdateAccountRequest;
import com.ledgerwatch.accountservice.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
public class AccountController {
    
    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/{id}")
    public AccountResponse getAccountById(@PathVariable UUID id) {
        return AccountResponse.from(accountService.getById(id));
    }

    @GetMapping
    public List<AccountResponse> getAllAccounts() {
        return accountService.getAll().stream()
            .map(AccountResponse::from)
            .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse createAccount(@Valid @RequestBody CreateAccountRequest request) {
        return AccountResponse.from(accountService.createAccount(request));
    }

    @PatchMapping("/{id}")
    public AccountResponse updateAccount(@PathVariable UUID id, @Valid @RequestBody UpdateAccountRequest request) {
        return AccountResponse.from(accountService.updateAccount(id, request));
    }
}
