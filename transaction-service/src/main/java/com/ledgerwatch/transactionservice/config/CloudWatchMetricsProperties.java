package com.ledgerwatch.transactionservice.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code metrics.cloudwatch.*}: where and how often {@link CloudWatchMetricsConfig} pushes metrics.
 *
 * @param enabled whether to push at all; Terraform turns it on for ECS
 * @param namespace CloudWatch namespace the metrics land in; the task role may only write this one
 * @param step how often to push, and so the period each data point covers
 */
@ConfigurationProperties("metrics.cloudwatch")
public record CloudWatchMetricsProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("LedgerWatch") String namespace,
    @DefaultValue("1m") Duration step) {}
