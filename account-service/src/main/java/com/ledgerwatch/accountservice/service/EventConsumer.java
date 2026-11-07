package com.ledgerwatch.accountservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerwatch.accountservice.dto.EventEnvelope;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Consumes events published by other services. Delivery is at-least-once, so every event is applied
 * at most once per event id (see {@link AccountService#applyEventBalanceDelta}).
 *
 * <p>Malformed or unsupported events throw {@link IllegalArgumentException} (HTTP 400), which the
 * publisher treats as final.
 */
@Service
public class EventConsumer {

  public static final String TRANSACTION_CREATED = "TransactionCreated";

  private final AccountService accountService;
  private final ObjectMapper objectMapper;

  public EventConsumer(AccountService accountService, ObjectMapper objectMapper) {
    this.accountService = accountService;
    this.objectMapper = objectMapper;
  }

  /**
   * @return true if the event changed state now; false if it was a redelivery
   */
  public boolean consume(EventEnvelope event) {
    if (!TRANSACTION_CREATED.equals(event.eventType())) {
      throw new IllegalArgumentException("Unsupported event type: " + event.eventType());
    }
    TransactionCreated payload = parse(event);
    // adjustmentId = transaction id, the same key the synchronous POST /transactions call uses, so
    // a change that call already made is replayed rather than applied a second time.
    return accountService.applyEventBalanceDelta(
        event.eventId(),
        event.eventType(),
        payload.accountId(),
        payload.balanceDelta(),
        payload.transactionId());
  }

  private TransactionCreated parse(EventEnvelope event) {
    TransactionCreated payload;
    try {
      payload = objectMapper.treeToValue(event.payload(), TransactionCreated.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(
          "Unreadable " + TRANSACTION_CREATED + " payload: " + e.getOriginalMessage(), e);
    }
    if (payload == null
        || payload.transactionId() == null
        || payload.accountId() == null
        || payload.type() == null
        || payload.amount() == null) {
      throw new IllegalArgumentException(
          TRANSACTION_CREATED + " payload requires transactionId, accountId, type and amount");
    }
    BigDecimal amount = payload.amount();
    if (amount.signum() <= 0
        || amount.stripTrailingZeros().scale() > 4
        || amount.precision() - amount.scale() > 15) {
      throw new IllegalArgumentException(
          "amount must be positive with at most 15 integer digits and 4 decimal places");
    }
    return payload;
  }

  /** The fields of transaction-service's {@code TransactionCreatedEvent} that matter here. */
  record TransactionCreated(UUID transactionId, UUID accountId, Type type, BigDecimal amount) {
    enum Type {
      CREDIT,
      DEBIT
    }

    BigDecimal balanceDelta() {
      return type == Type.CREDIT ? amount : amount.negate();
    }
  }
}
