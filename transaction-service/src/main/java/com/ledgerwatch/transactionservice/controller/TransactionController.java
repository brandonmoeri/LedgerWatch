package com.ledgerwatch.transactionservice.controller;

import com.ledgerwatch.transactionservice.dto.CreateTransactionRequest;
import com.ledgerwatch.transactionservice.dto.TransactionResponse;
import com.ledgerwatch.transactionservice.dto.UpdateTransactionRequest;
import com.ledgerwatch.transactionservice.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/transactions")
public class TransactionController {
    
    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @GetMapping("/{id}")
    public TransactionResponse getTransactionById(@PathVariable UUID id) {
        return TransactionResponse.from(transactionService.getById(id));
    }

    @GetMapping
    public List<TransactionResponse> getAllTransactions() {
        return transactionService.getAll().stream()
            .map(TransactionResponse::from)
            .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse createTransaction(@Valid @RequestBody CreateTransactionRequest request) {
        return TransactionResponse.from(transactionService.createTransaction(request));
    }

    @PatchMapping("/{id}")
    public TransactionResponse updateTransaction(@PathVariable UUID id, @Valid @RequestBody UpdateTransactionRequest request) {
        return TransactionResponse.from(transactionService.updateTransaction(id, request));
    }
}
