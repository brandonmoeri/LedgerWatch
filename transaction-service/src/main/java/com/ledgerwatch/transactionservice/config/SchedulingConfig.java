package com.ledgerwatch.transactionservice.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} methods (today only the outbox publisher's poll). Off when {@code
 * outbox.publisher.enabled=false}, as in tests, which call the publisher directly.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "outbox.publisher.enabled", havingValue = "true")
public class SchedulingConfig {}
