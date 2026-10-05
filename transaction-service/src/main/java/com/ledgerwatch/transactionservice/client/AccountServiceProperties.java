package com.ledgerwatch.transactionservice.client;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Connection settings for calls to account-service ({@code account-service.*}). */
@Validated
@ConfigurationProperties("account-service")
public record AccountServiceProperties(
    @NotNull URI baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {}
