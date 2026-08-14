package com.ledgerwatch.accountservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerwatch.accountservice.TestcontainersConfiguration;
import com.ledgerwatch.accountservice.dto.CreateAccountRequest;
import com.ledgerwatch.accountservice.repository.AccountRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SuppressWarnings("null")
class CreateAccountIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountRepository accountRepository;
    @Autowired Flyway flyway;

    // MockMvc dispatches through the servlet container and commits its own transactions,
    // so @Transactional rollback does not apply here — delete explicitly instead.
    @AfterEach
    void cleanUp() {
        accountRepository.deleteAll();
    }

    @Test
    void flyway_appliedMigrationsOnStartup() {
        assertThat(flyway.info().applied())
                .as("Flyway must have applied at least V1__baseline.sql")
                .isNotEmpty();
    }

    @Test
    void createAccount_validRequest_returns201WithBody() throws Exception {
        var request = new CreateAccountRequest("Alice Ledger", new BigDecimal("500.00"));

        mockMvc.perform(post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.ownerName").value("Alice Ledger"))
            .andExpect(jsonPath("$.balance").value(500.00))
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void createAccount_blankOwnerName_returns400WithFieldError() throws Exception {
        var request = new CreateAccountRequest("", null);

        mockMvc.perform(post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.ownerName").isNotEmpty());
    }

    @Test
    void createAccount_negativeBalance_returns400WithFieldError() throws Exception {
        var request = new CreateAccountRequest("Bob", new BigDecimal("-1.00"));

        mockMvc.perform(post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.initialBalance").isNotEmpty());
    }
}
