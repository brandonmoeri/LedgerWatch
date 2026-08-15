package com.ledgerwatch.transactionservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.dto.CreateTransactionRequest;
import com.ledgerwatch.transactionservice.repository.TransactionRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@SuppressWarnings("null")
class CreateTransactionIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TransactionRepository transactionRepository;
    @Autowired Flyway flyway;

    // MockMvc dispatches through the servlet container and commits its own transactions,
    // so @Transactional rollback does not apply here — delete explicitly instead.
    @AfterEach
    void cleanUp() {
        transactionRepository.deleteAll();
    }

    @Test
    void flyway_appliedMigrationsOnStartup() {
        assertThat(flyway.info().applied())
                .as("Flyway must have applied at least V1__baseline.sql")
                .isNotEmpty();
    }

    @Test
    void createTransaction_validRequest_returns201WithBody() throws Exception {
        var request = new CreateTransactionRequest(UUID.randomUUID(), TransactionType.CREDIT, new BigDecimal("250.00"), "salary");

        mockMvc.perform(post("/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.type").value("CREDIT"))
            .andExpect(jsonPath("$.amount").value(250.00))
            .andExpect(jsonPath("$.status").value("POSTED"));
    }

    @Test
    void createTransaction_nullAccountId_returns400WithFieldError() throws Exception {
        var request = new CreateTransactionRequest(null, TransactionType.DEBIT, new BigDecimal("10.00"), null);

        mockMvc.perform(post("/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.accountId").isNotEmpty());
    }

    @Test
    void createTransaction_zeroAmount_returns400WithFieldError() throws Exception {
        var request = new CreateTransactionRequest(UUID.randomUUID(), TransactionType.DEBIT, BigDecimal.ZERO, null);

        mockMvc.perform(post("/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.amount").isNotEmpty());
    }
}