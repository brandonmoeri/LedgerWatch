package com.ledgerwatch.transactionservice.controller;

import com.ledgerwatch.transactionservice.dto.FraudFlagResponse;
import com.ledgerwatch.transactionservice.repository.FraudFlagRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.data.web.SortDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * An account's fraud flags. Served by transaction-service, which screens and stores them, although
 * the path is under {@code /accounts}; the load balancer routes it here (see infra/aws/main.tf).
 */
@RestController
public class AccountFraudFlagController {

  private final FraudFlagRepository flags;

  public AccountFraudFlagController(FraudFlagRepository flags) {
    this.flags = flags;
  }

  @GetMapping("/accounts/{accountId}/flags")
  @Operation(
      summary = "List an account's fraud flags",
      description =
          "One entry per fraud rule that fired on one of the account's transactions, newest "
              + "first. blocked is true when the transaction was rejected as SUSPECTED_FRAUD and "
              + "false when it was posted anyway (MONITOR mode). Supports pagination (page, size) "
              + "and sorting (sort). An account with no flags, or one this service has never "
              + "seen, returns an empty page.")
  @ApiResponse(responseCode = "200", description = "Flags returned")
  public PagedModel<FraudFlagResponse> getAccountFlags(
      @PathVariable UUID accountId,
      @PageableDefault(size = 20)
          @SortDefault.SortDefaults({
            @SortDefault(sort = "flaggedAt", direction = Sort.Direction.DESC),
            // Flags from one screening share flaggedAt; a tiebreaker keeps pages stable.
            @SortDefault(sort = "id", direction = Sort.Direction.DESC)
          })
          Pageable pageable) {
    return new PagedModel<>(
        flags.findByAccountId(accountId, pageable).map(FraudFlagResponse::from));
  }
}
