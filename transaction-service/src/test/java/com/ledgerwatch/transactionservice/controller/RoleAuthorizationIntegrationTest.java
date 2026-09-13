package com.ledgerwatch.transactionservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.dto.CreateTransactionRequest;
import com.ledgerwatch.transactionservice.dto.UpdateTransactionRequest;
import com.ledgerwatch.transactionservice.repository.TransactionRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.ledgerwatch.transactionservice.TestcontainersConfiguration;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SuppressWarnings("null")
class RoleAuthorizationIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TransactionRepository transactionRepository;
    @Value("${jwt.secret}") String jwtSecret;

    @AfterEach
    void cleanUp() {
        transactionRepository.deleteAll();
    }

    private String mintToken(List<String> roles) throws Exception {
        var signer = new MACSigner(jwtSecret.getBytes(StandardCharsets.UTF_8));
        var claims = new JWTClaimsSet.Builder()
            .subject("test-user")
            .claim("roles", roles)
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
            .build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(signer);
        return jwt.serialize();
    }

    private String createTransactionAsAdmin() throws Exception {
        var body = objectMapper.writeValueAsString(
            new CreateTransactionRequest(UUID.randomUUID(), TransactionType.DEBIT, new BigDecimal("100.00"), null));
        var result = mockMvc.perform(post("/transactions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void noToken_postTransactions_returns401() throws Exception {
        var body = objectMapper.writeValueAsString(
            new CreateTransactionRequest(UUID.randomUUID(), TransactionType.CREDIT, new BigDecimal("50.00"), null));

        mockMvc.perform(post("/transactions")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void noToken_patchTransactions_returns401() throws Exception {
        var body = objectMapper.writeValueAsString(new UpdateTransactionRequest(null, "no token"));

        mockMvc.perform(patch("/transactions/{id}", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void analystRole_postTransactions_returns403() throws Exception {
        var body = objectMapper.writeValueAsString(
            new CreateTransactionRequest(UUID.randomUUID(), TransactionType.CREDIT, new BigDecimal("50.00"), null));

        mockMvc.perform(post("/transactions")
                .header("Authorization", "Bearer " + mintToken(List.of("ANALYST")))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
    }

    @Test
    void analystRole_patchTransactions_returns403() throws Exception {
        var id = createTransactionAsAdmin();
        var body = objectMapper.writeValueAsString(new UpdateTransactionRequest(null, "attempted edit"));

        mockMvc.perform(patch("/transactions/{id}", id)
                .header("Authorization", "Bearer " + mintToken(List.of("ANALYST")))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminRole_postTransactions_returns201() throws Exception {
        var body = objectMapper.writeValueAsString(
            new CreateTransactionRequest(UUID.randomUUID(), TransactionType.CREDIT, new BigDecimal("50.00"), null));

        mockMvc.perform(post("/transactions")
                .header("Authorization", "Bearer " + mintToken(List.of("ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
    }

    @Test
    void adminRole_patchTransactions_returns200() throws Exception {
        var id = createTransactionAsAdmin();
        var body = objectMapper.writeValueAsString(new UpdateTransactionRequest(null, "admin edit"));

        mockMvc.perform(patch("/transactions/{id}", id)
                .header("Authorization", "Bearer " + mintToken(List.of("ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
    }
}
