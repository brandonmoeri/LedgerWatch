package com.ledgerwatch.accountservice.controller;

import com.ledgerwatch.accountservice.dto.EventEnvelope;
import com.ledgerwatch.accountservice.dto.EventReceipt;
import com.ledgerwatch.accountservice.service.EventConsumer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Event intake for other services' outbox publishers. Callers need the SERVICE role. */
@RestController
@RequestMapping("/internal/events")
@PreAuthorize("hasRole('SERVICE')")
@Tag(name = "internal", description = "Service-to-service endpoints (SERVICE role only)")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(
    responseCode = "401",
    description = "Missing or invalid bearer token",
    content = @Content)
public class InternalEventController {

  private final EventConsumer eventConsumer;

  public InternalEventController(EventConsumer eventConsumer) {
    this.eventConsumer = eventConsumer;
  }

  @PostMapping
  @Operation(
      summary = "Consume an event",
      description =
          "Applies an event at most once per eventId. A redelivered event returns 200 with"
              + " duplicate=true and changes nothing, so publishers can deliver at-least-once."
              + " Supported types: TransactionCreated (payload: transactionId, accountId, type"
              + " CREDIT|DEBIT, amount), which applies the balance change under adjustmentId ="
              + " transactionId. A rejected event is not recorded and may be delivered again.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Event applied, or already processed"),
    @ApiResponse(
        responseCode = "400",
        description = "Validation failed, unsupported eventType, or malformed payload",
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
        description =
            "Account is FROZEN/CLOSED, concurrent-modification retries were exhausted, or the"
                + " eventId or transactionId was already used with different values",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "422",
        description = "Insufficient funds (type urn:ledgerwatch:problem:insufficient-funds)",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  })
  public EventReceipt consume(@Valid @RequestBody EventEnvelope event) {
    boolean applied = eventConsumer.consume(event);
    return new EventReceipt(event.eventId(), !applied);
  }
}
