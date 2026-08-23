package com.ledgerwatch.transactionservice.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

@OpenAPIDefinition(
    info = @Info(
        title = "LedgerWatch Transaction Service API",
        version = "0.1.0",
        description = "Manages transaction creation, lookup, and updates."
    )
)
public class OpenApiConfig {
}
