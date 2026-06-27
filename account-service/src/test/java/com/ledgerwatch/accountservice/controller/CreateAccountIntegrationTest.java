package com.ledgerwatch.accountservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerwatch.accountservice.dto.CreateAccountRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@SuppressWarnings("null")
class CreateAccountIntegrationTest {
    
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

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
