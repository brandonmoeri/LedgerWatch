package com.ledgerwatch.transactionservice.controller;

import com.ledgerwatch.transactionservice.domain.TransactionStatus;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.dto.CreateTransactionRequest;
import com.ledgerwatch.transactionservice.dto.CreateTransactionResult;
import com.ledgerwatch.transactionservice.dto.DashboardSummaryResponse;
import com.ledgerwatch.transactionservice.dto.TransactionResponse;
import com.ledgerwatch.transactionservice.dto.UpdateTransactionRequest;
import com.ledgerwatch.transactionservice.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

  static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
  static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";

  private final TransactionService transactionService;

  public TransactionController(TransactionService transactionService) {
    this.transactionService = transactionService;
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get a transaction by id")
  @ApiResponse(responseCode = "200", description = "Transaction found")
  @ApiResponse(
      responseCode = "404",
      description = "Transaction not found",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  public TransactionResponse getTransactionById(@PathVariable UUID id) {
    return TransactionResponse.from(transactionService.getById(id));
  }

  @GetMapping
  @Operation(
      summary = "List transactions",
      description =
          "Supports pagination (page, size), sorting (sort), and filtering by accountId, type, "
              + "status, description, and createdAt date range (createdFrom, createdTo).")
  @ApiResponse(responseCode = "200", description = "Transactions returned")
  public PagedModel<TransactionResponse> getAllTransactions(
      @RequestParam(required = false) UUID accountId,
      @RequestParam(required = false) TransactionType type,
      @RequestParam(required = false) TransactionStatus status,
      @RequestParam(required = false) String description,
      @RequestParam(required = false) Instant createdFrom,
      @RequestParam(required = false) Instant createdTo,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    Page<TransactionResponse> page =
        transactionService
            .getAll(accountId, type, status, description, createdFrom, createdTo, pageable)
            .map(TransactionResponse::from);
    return new PagedModel<>(page);
  }

  @GetMapping("/summary")
  @Operation(
      summary = "Get dashboard summary aggregates",
      description =
          "Computes balance-over-time (cumulative daily net of posted transactions) and total "
              + "spend by transaction type, optionally filtered by accountId and a createdAt "
              + "date range (createdFrom, createdTo). Only POSTED transactions are included.")
  @ApiResponse(responseCode = "200", description = "Summary computed")
  public DashboardSummaryResponse getDashboardSummary(
      @RequestParam(required = false) UUID accountId,
      @RequestParam(required = false) Instant createdFrom,
      @RequestParam(required = false) Instant createdTo) {
    return transactionService.getDashboardSummary(accountId, createdFrom, createdTo);
  }

  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Create a transaction",
      description =
          "Send an Idempotency-Key header to make retries safe. A repeat of the same request "
              + "with the same key returns the original 201 response, with an "
              + "Idempotent-Replayed: true header, and does not create a second transaction or "
              + "adjust the balance again. Keys are scoped to the caller and are only recorded "
              + "when the request succeeds.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Transaction created, or the original response replayed"),
    @ApiResponse(
        responseCode = "400",
        description = "Validation failed",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "422",
        description =
            "Idempotency-Key was already used with a different request, or a DEBIT exceeds the"
                + " account balance (type urn:ledgerwatch:problem:insufficient-funds)",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  })
  public ResponseEntity<TransactionResponse> createTransaction(
      @Valid @RequestBody CreateTransactionRequest request,
      @Parameter(description = "Client-generated key (e.g. a UUID), at most 255 characters")
          @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false)
          String idempotencyKey,
      Authentication authentication) {
    CreateTransactionResult result =
        transactionService.createTransaction(request, authentication.getName(), idempotencyKey);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) {
      response.header(IDEMPOTENT_REPLAYED_HEADER, "true");
    }
    return response.body(result.response());
  }

  @PatchMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Update a transaction")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Transaction updated"),
    @ApiResponse(
        responseCode = "400",
        description = "Validation failed",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "404",
        description = "Transaction not found",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "409",
        description = "Voided transaction cannot be modified",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  })
  public TransactionResponse updateTransaction(
      @PathVariable UUID id, @Valid @RequestBody UpdateTransactionRequest request) {
    return TransactionResponse.from(transactionService.updateTransaction(id, request));
  }
}
