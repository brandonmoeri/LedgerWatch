package com.ledgerwatch.accountservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerwatch.accountservice.domain.AccountStatus;
import com.ledgerwatch.accountservice.dto.CreateAccountRequest;
import com.ledgerwatch.accountservice.dto.UpdateAccountRequest;
import com.ledgerwatch.accountservice.repository.AccountRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@SuppressWarnings("null")
public class AccountControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountRepository accountRepository;

    @AfterEach
    void cleanUp() {
        accountRepository.deleteAll();
    }

    private String createAccount(String name) throws Exception {
        var body = objectMapper.writeValueAsString(new CreateAccountRequest(name, null));
        var result = mockMvc.perform(post("/accounts")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void getAccount_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/accounts/{id}", UUID.randomUUID()).with(jwt()))
            .andExpect(status().isNotFound());
    }

    @Test
    void updateAccount_unknownId_returns404() throws Exception {
        var body = objectMapper.writeValueAsString(new UpdateAccountRequest("X", null));
        mockMvc.perform(patch("/accounts/{id}", UUID.randomUUID())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isNotFound());
    }

    @Test
    void updateAccount_validRequest_returns200WithUpdatedFields() throws Exception {
        var id = createAccount("Before");
        var body = objectMapper.writeValueAsString(new UpdateAccountRequest("After", AccountStatus.FROZEN));
        mockMvc.perform(patch("/accounts/{id}", id)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ownerName").value("After"))
            .andExpect(jsonPath("$.status").value("FROZEN"));
    }

    @Test
    void getAllAccounts_returnsPagedResultsFilteredByStatusAndOwnerName() throws Exception {
        var activeId = createAccount("Alice Active");
        createAccount("Bob Active");
        var close = objectMapper.writeValueAsString(new UpdateAccountRequest(null, AccountStatus.CLOSED));
        var closedId = createAccount("Closed Carl");
        mockMvc.perform(patch("/accounts/{id}", closedId)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(close))
            .andExpect(status().isOk());

        mockMvc.perform(get("/accounts")
                .with(jwt())
                .param("status", "ACTIVE")
                .param("ownerName", "alice")
                .param("page", "0")
                .param("size", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(activeId))
            .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void updateAccount_closedAccount_returns409() throws Exception {
        var id = createAccount("To Close");
        var close = objectMapper.writeValueAsString(new UpdateAccountRequest(null, AccountStatus.CLOSED));
        mockMvc.perform(patch("/accounts/{id}", id)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(close))
            .andExpect(status().isOk());

        var retry = objectMapper.writeValueAsString(new UpdateAccountRequest("Attempt", null));
        mockMvc.perform(patch("/accounts/{id}", id)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(retry))
            .andExpect(status().isConflict());
    }
}
