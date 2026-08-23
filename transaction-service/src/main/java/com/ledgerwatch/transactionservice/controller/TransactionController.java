package com.ledgerwatch.transactionservice.controller;

import com.ledgerwatch.transactionservice.dto.CreateTransactionRequest;
import com.ledgerwatch.transactionservice.dto.TransactionResponse;
import com.ledgerwatch.transactionservice.dto.UpdateTransactionRequest;
import com.ledgerwatch.transactionservice.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
    @Operation(summary = "Get a transaction by id")
    @ApiResponse(responseCode = "200", description = "Transaction found")
    @ApiResponse(responseCode = "404", description = "Transaction not found",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public TransactionResponse getTransactionById(@PathVariable UUID id) {
        return TransactionResponse.from(transactionService.getById(id));
    }

    @GetMapping
    @Operation(summary = "List all transactions")
    @ApiResponse(responseCode = "200", description = "Transactions returned")
    public List<TransactionResponse> getAllTransactions() {
        return transactionService.getAll().stream()
            .map(TransactionResponse::from)
            .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a transaction")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Transaction created"),
        @ApiResponse(responseCode = "400", description = "Validation failed",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public TransactionResponse createTransaction(@Valid @RequestBody CreateTransactionRequest request) {
        return TransactionResponse.from(transactionService.createTransaction(request));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a transaction")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Transaction updated"),
        @ApiResponse(responseCode = "400", description = "Validation failed",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Transaction not found",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Voided transaction cannot be modified",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public TransactionResponse updateTransaction(@PathVariable UUID id, @Valid @RequestBody UpdateTransactionRequest request) {
        return TransactionResponse.from(transactionService.updateTransaction(id, request));
    }
}
