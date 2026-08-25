package com.ledgerwatch.transactionservice.controller;

import com.ledgerwatch.transactionservice.domain.TransactionStatus;
import com.ledgerwatch.transactionservice.domain.TransactionType;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

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
    @Operation(summary = "List transactions",
        description = "Supports pagination (page, size), sorting (sort), and filtering by accountId, type, status, and description.")
    @ApiResponse(responseCode = "200", description = "Transactions returned")
    public PagedModel<TransactionResponse> getAllTransactions(
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) String description,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<TransactionResponse> page = transactionService.getAll(accountId, type, status, description, pageable)
            .map(TransactionResponse::from);
        return new PagedModel<>(page);
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
