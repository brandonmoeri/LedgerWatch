package com.ledgerwatch.accountservice.controller;

import com.ledgerwatch.accountservice.dto.AccountResponse;
import com.ledgerwatch.accountservice.dto.BalanceAdjustmentRequest;
import com.ledgerwatch.accountservice.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Service-to-service endpoints. Not intended for end users; callers need the SERVICE role. */
@RestController
@RequestMapping("/internal/accounts")
@PreAuthorize("hasRole('SERVICE')")
@Tag(name = "internal", description = "Service-to-service endpoints (SERVICE role only)")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(
    responseCode = "401",
    description = "Missing or invalid bearer token",
    content = @Content)
public class InternalAccountController {

  private final AccountService accountService;

  public InternalAccountController(AccountService accountService) {
    this.accountService = accountService;
  }

  @PostMapping("/{id}/balance-adjustments")
  @Operation(
      summary = "Apply a balance delta",
      description =
          "Adds a signed delta to the account balance. Only ACTIVE accounts accept changes,"
              + " and a debit may not take the balance below zero."
              + " Concurrent modification is detected with optimistic locking and retried"
              + " server-side; 409 is returned only if every retry conflicts.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Delta applied"),
    @ApiResponse(
        responseCode = "400",
        description = "Validation failed (missing, zero, or over-precise delta)",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "403",
        description = "Caller lacks the SERVICE role",
        content = @Content),
    @ApiResponse(
        responseCode = "404",
        description = "Account not found",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "409",
        description = "Account is FROZEN/CLOSED, or concurrent-modification retries were exhausted",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "422",
        description =
            "Insufficient funds: a negative delta would take the balance below zero"
                + " (type urn:ledgerwatch:problem:insufficient-funds)",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  })
  public AccountResponse applyBalanceDelta(
      @PathVariable UUID id, @Valid @RequestBody BalanceAdjustmentRequest request) {
    return AccountResponse.from(accountService.applyBalanceDelta(id, request.delta()));
  }
}
