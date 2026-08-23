package com.ledgerwatch.accountservice.controller;

import com.ledgerwatch.accountservice.dto.AccountResponse;
import com.ledgerwatch.accountservice.dto.CreateAccountRequest;
import com.ledgerwatch.accountservice.dto.UpdateAccountRequest;
import com.ledgerwatch.accountservice.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "401", description = "Missing or invalid bearer token", content = @Content)
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an account by id")
    @ApiResponse(responseCode = "200", description = "Account found")
    @ApiResponse(responseCode = "404", description = "Account not found",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public AccountResponse getAccountById(@PathVariable UUID id) {
        return AccountResponse.from(accountService.getById(id));
    }

    @GetMapping
    @Operation(summary = "List all accounts")
    @ApiResponse(responseCode = "200", description = "Accounts returned")
    public List<AccountResponse> getAllAccounts() {
        return accountService.getAll().stream()
            .map(AccountResponse::from)
            .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create an account", description = "Requires the ADMIN role.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Account created"),
        @ApiResponse(responseCode = "400", description = "Validation failed",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN role", content = @Content)
    })
    public AccountResponse createAccount(@Valid @RequestBody CreateAccountRequest request) {
        return AccountResponse.from(accountService.createAccount(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update an account", description = "Requires the ADMIN role.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Account updated"),
        @ApiResponse(responseCode = "400", description = "Validation failed",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN role", content = @Content),
        @ApiResponse(responseCode = "404", description = "Account not found",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Account is closed and cannot be modified",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public AccountResponse updateAccount(@PathVariable UUID id, @Valid @RequestBody UpdateAccountRequest request) {
        return AccountResponse.from(accountService.updateAccount(id, request));
    }
}
