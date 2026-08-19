package com.ledgerwatch.accountservice.service;

import com.ledgerwatch.accountservice.security.DevUser;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Dev-only credential store and token issuer. There is no user database yet;
 * this exists so the frontend has a real login flow to hit instead of a stub.
 * Swap the in-memory {@code users} map for a real user store when one exists.
 */
@Service
public class AuthService {

    private static final long TOKEN_TTL_SECONDS = 3600;

    private final String jwtSecret;
    private final PasswordEncoder passwordEncoder;
    private final Map<String, DevUser> users;

    public AuthService(@Value("${jwt.secret}") String jwtSecret, PasswordEncoder passwordEncoder) {
        this.jwtSecret = jwtSecret;
        this.passwordEncoder = passwordEncoder;
        this.users = Map.of(
            "admin", new DevUser("admin", passwordEncoder.encode("admin123"), List.of("ADMIN")),
            "analyst", new DevUser("analyst", passwordEncoder.encode("analyst123"), List.of("ANALYST"))
        );
    }

    public LoginResult login(String username, String password) {
        DevUser user = users.get(username);
        if (user == null || !passwordEncoder.matches(password, user.passwordHash())) {
            throw new BadCredentialsException("Invalid username or password");
        }
        return new LoginResult(issueToken(user), TOKEN_TTL_SECONDS);
    }

    private String issueToken(DevUser user) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(user.username())
                .claim("roles", user.roles())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(TOKEN_TTL_SECONDS)))
                .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(jwtSecret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to issue token", e);
        }
    }

    public record LoginResult(String token, long expiresIn) {
    }
}
