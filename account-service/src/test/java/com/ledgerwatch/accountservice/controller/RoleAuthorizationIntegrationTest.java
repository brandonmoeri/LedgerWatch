package com.ledgerwatch.accountservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerwatch.accountservice.dto.CreateAccountRequest;
import com.ledgerwatch.accountservice.repository.AccountRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@SuppressWarnings("null")
class RoleAuthorizationIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountRepository accountRepository;
    @Value("${jwt.secret}") String jwtSecret;

    @AfterEach
    void cleanUp() {
        accountRepository.deleteAll();
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

    @Test
    void analystRole_postAccounts_returns403() throws Exception {
        var body = objectMapper.writeValueAsString(new CreateAccountRequest("Analyst Attempt", null));

        mockMvc.perform(post("/accounts")
                .header("Authorization", "Bearer " + mintToken(List.of("ANALYST")))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminRole_postAccounts_returns201() throws Exception {
        var body = objectMapper.writeValueAsString(new CreateAccountRequest("Admin Created", null));

        mockMvc.perform(post("/accounts")
                .header("Authorization", "Bearer " + mintToken(List.of("ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
    }
}
