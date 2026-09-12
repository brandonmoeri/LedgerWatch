package com.ledgerwatch.accountservice.controller;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class JwtSecurityIntegrationTest {
    
    @Autowired MockMvc mockMvc;
    @Value("${jwt.secret}") String jwtSecret;

    private String mintToken(String secret) throws Exception {
        var signer = new MACSigner(secret.getBytes(StandardCharsets.UTF_8));
        var claims = new JWTClaimsSet.Builder()
            .subject("test-user")
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
            .build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(signer);
        return jwt.serialize();
    }

    @Test
    void noToken_returns401() throws Exception {
        mockMvc.perform(get("/accounts")).andExpect(status().isUnauthorized());
    }

    @Test
    void validSignedToken_returns200() throws Exception {
        mockMvc.perform(get("/accounts")
                .header("Authorization", "Bearer " + mintToken(jwtSecret)))
            .andExpect(status().isOk());
    }

    @Test
    void tokenSignedWithWrongSecret_returns401() throws Exception {
        mockMvc.perform(get("/accounts")
                .header("Authorization", "Bearer " + mintToken("wrong-secret-wrong-secret-wrong!!")))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedSignature_returns401() throws Exception {
        String token = mintToken(jwtSecret);
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "InvalidsignatureXXX";
        mockMvc.perform(get("/accounts")
                .header("Authorization", "Bearer " + tampered))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void health_permitsWithoutToken() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
