package com.ledgerwatch.transactionservice.client;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mints short-lived service-to-service JWTs carrying the {@code SERVICE} role, signed with the
 * shared {@code jwt.secret} that every LedgerWatch service already validates against. Tokens are
 * cached and re-minted shortly before they expire.
 */
@Component
public class ServiceTokenProvider {

  static final String SUBJECT = "transaction-service";
  static final Duration TOKEN_TTL = Duration.ofMinutes(5);
  // Re-mint this long before expiry so a token never lapses in flight or under clock skew.
  static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

  private final MACSigner signer;
  private final Clock clock;
  private volatile CachedToken cached;

  @Autowired
  public ServiceTokenProvider(@Value("${jwt.secret}") String jwtSecret) {
    this(jwtSecret, Clock.systemUTC());
  }

  ServiceTokenProvider(String jwtSecret, Clock clock) {
    try {
      this.signer = new MACSigner(jwtSecret.getBytes(StandardCharsets.UTF_8));
    } catch (JOSEException e) {
      throw new IllegalArgumentException("jwt.secret is not a valid HS256 key", e);
    }
    this.clock = clock;
  }

  public String token() {
    Instant now = clock.instant();
    CachedToken current = cached;
    if (current == null || now.isAfter(current.expiresAt().minus(REFRESH_MARGIN))) {
      current = mint(now);
      cached = current;
    }
    return current.value();
  }

  private CachedToken mint(Instant now) {
    Instant expiresAt = now.plus(TOKEN_TTL);
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject(SUBJECT)
            .claim("roles", List.of("SERVICE"))
            .issueTime(Date.from(now))
            .expirationTime(Date.from(expiresAt))
            .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    try {
      jwt.sign(signer);
    } catch (JOSEException e) {
      throw new IllegalStateException("Failed to sign service token", e);
    }
    return new CachedToken(jwt.serialize(), expiresAt);
  }

  private record CachedToken(String value, Instant expiresAt) {}
}
