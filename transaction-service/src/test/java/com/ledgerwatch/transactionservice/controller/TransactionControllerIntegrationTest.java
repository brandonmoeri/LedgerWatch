package com.ledgerwatch.transactionservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerwatch.transactionservice.domain.TransactionStatus;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.dto.CreateTransactionRequest;
import com.ledgerwatch.transactionservice.dto.UpdateTransactionRequest;
import com.ledgerwatch.transactionservice.repository.TransactionRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@SuppressWarnings("null")
class TransactionControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TransactionRepository transactionRepository;

    @AfterEach
    void cleanUp() {
        transactionRepository.deleteAll();
    }

    private String createTransaction() throws Exception {
        return createTransaction(UUID.randomUUID(), TransactionType.DEBIT, new BigDecimal("100.00"), null);
    }

    private String createTransaction(UUID accountId, TransactionType type, BigDecimal amount, String description) throws Exception {
        var body = objectMapper.writeValueAsString(
            new CreateTransactionRequest(accountId, type, amount, description));
        var result = mockMvc.perform(post("/transactions")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void getTransaction_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/transactions/{id}", UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }

    @Test
    void updateTransaction_unknownId_returns404() throws Exception {
        var body = objectMapper.writeValueAsString(new UpdateTransactionRequest(TransactionStatus.VOIDED, null));
        mockMvc.perform(patch("/transactions/{id}", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isNotFound());
    }

    @Test
    void updateTransaction_validStatus_returns200() throws Exception {
        var id = createTransaction();
        var body = objectMapper.writeValueAsString(new UpdateTransactionRequest(TransactionStatus.VOIDED, "cancelled"));
        mockMvc.perform(patch("/transactions/{id}", id)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("VOIDED"))
            .andExpect(jsonPath("$.description").value("cancelled"));
    }

    @Test
    void getAllTransactions_returnsPagedResultsFilteredByAccountIdAndType() throws Exception {
        var accountId = UUID.randomUUID();
        var creditId = createTransaction(accountId, TransactionType.CREDIT, new BigDecimal("25.00"), "salary");
        createTransaction(accountId, TransactionType.DEBIT, new BigDecimal("10.00"), "groceries");
        createTransaction(UUID.randomUUID(), TransactionType.CREDIT, new BigDecimal("999.00"), "other account");

        mockMvc.perform(get("/transactions")
                .param("accountId", accountId.toString())
                .param("type", "CREDIT")
                .param("page", "0")
                .param("size", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(creditId))
            .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void updateTransaction_alreadyVoided_returns409() throws Exception {
        var id = createTransaction();
        var void1 = objectMapper.writeValueAsString(new UpdateTransactionRequest(TransactionStatus.VOIDED, null));
        mockMvc.perform(patch("/transactions/{id}", id)
                .contentType(MediaType.APPLICATION_JSON).content(void1))
            .andExpect(status().isOk());

        // any further mutation on a voided transaction is a conflict
        var void2 = objectMapper.writeValueAsString(new UpdateTransactionRequest(TransactionStatus.POSTED, null));
        mockMvc.perform(patch("/transactions/{id}", id)
                .contentType(MediaType.APPLICATION_JSON).content(void2))
            .andExpect(status().isConflict());
    }
}