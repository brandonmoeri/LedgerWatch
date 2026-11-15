package com.ledgerwatch.insightsservice.ai;

import com.ledgerwatch.insightsservice.client.LedgerApiClient;
import com.ledgerwatch.insightsservice.client.LedgerViews.Account;
import com.ledgerwatch.insightsservice.client.LedgerViews.FraudFlag;
import com.ledgerwatch.insightsservice.client.LedgerViews.Page;
import com.ledgerwatch.insightsservice.client.LedgerViews.Transaction;
import com.ledgerwatch.insightsservice.client.LedgerViews.TransactionStatus;
import com.ledgerwatch.insightsservice.client.LedgerViews.TransactionType;
import java.time.Instant;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * The read-only tools Claude can call while answering a question.
 *
 * <p>Each call is made as the user who asked: {@link InsightsChat} puts their JWT in the {@link
 * ToolContext} under {@link #CALLER_TOKEN}, and every tool forwards it to the ledger service. The
 * tool context is never sent to Claude, so the model can neither see the token nor substitute
 * another identity. None of these tools change anything.
 */
@Component
public class LedgerTools {

  /** Tool-context key for the caller's raw bearer token. */
  public static final String CALLER_TOKEN = "ledgerwatch.callerToken";

  private final LedgerApiClient ledger;

  public LedgerTools(LedgerApiClient ledger) {
    this.ledger = ledger;
  }

  @Tool(
      description =
          "Search one account's transactions, newest first. Every filter except accountId is "
              + "optional. Each transaction lists the fraud rules that fired on it in flags. The "
              + "result is one page; page.totalElements says how many matched in all.")
  public Page<Transaction> searchTransactions(
      @ToolParam(description = "The account's id (a UUID)") UUID accountId,
      @ToolParam(required = false, description = "CREDIT or DEBIT") TransactionType type,
      @ToolParam(required = false, description = "PENDING, POSTED, REJECTED or VOIDED")
          TransactionStatus status,
      @ToolParam(required = false, description = "Text the description must contain")
          String description,
      @ToolParam(
              required = false,
              description = "Only transactions created at or after this ISO-8601 instant")
          Instant createdFrom,
      @ToolParam(
              required = false,
              description = "Only transactions created before this ISO-8601 instant")
          Instant createdTo,
      @ToolParam(required = false, description = "Zero-based page number; default 0") Integer page,
      @ToolParam(required = false, description = "Page size, 1 to 50; default 20") Integer size,
      ToolContext toolContext) {
    return ledger.searchTransactions(
        accountId,
        type,
        status,
        description,
        createdFrom,
        createdTo,
        page,
        size,
        callerToken(toolContext));
  }

  @Tool(description = "Get an account: owner name, status (ACTIVE, FROZEN, CLOSED) and balance.")
  public Account getAccountSummary(
      @ToolParam(description = "The account's id (a UUID)") UUID accountId,
      ToolContext toolContext) {
    return ledger.getAccount(accountId, callerToken(toolContext));
  }

  @Tool(
      description =
          "List the fraud flags raised on an account's transactions, newest first. blocked is true "
              + "when the transaction was rejected as suspected fraud, false when it was posted "
              + "anyway.")
  public Page<FraudFlag> getFlags(
      @ToolParam(description = "The account's id (a UUID)") UUID accountId,
      @ToolParam(required = false, description = "Zero-based page number; default 0") Integer page,
      @ToolParam(required = false, description = "Page size, 1 to 50; default 20") Integer size,
      ToolContext toolContext) {
    return ledger.getFlags(accountId, page, size, callerToken(toolContext));
  }

  /** Fails closed: a tool call with no caller token never reaches a ledger service. */
  static String callerToken(ToolContext toolContext) {
    Object token = toolContext == null ? null : toolContext.getContext().get(CALLER_TOKEN);
    if (token instanceof String s && !s.isBlank()) {
      return s;
    }
    throw new IllegalStateException("No caller token: ledger data is only read as the user");
  }
}
