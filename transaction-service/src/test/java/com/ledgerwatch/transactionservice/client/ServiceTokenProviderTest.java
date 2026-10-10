package com.ledgerwatch.transactionservice.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ServiceTokenProviderTest {

  private static final String SECRET = "test-secret-that-is-exactly-32b!!";

  /** Clock whose instant the test can move forward. */
  private static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }

  private final MutableClock clock = new MutableClock();
  private final ServiceTokenProvider provider = new ServiceTokenProvider(SECRET, clock);

  @Test
  void token_isSignedWithSharedSecretAndCarriesServiceRole() throws Exception {
    SignedJWT jwt = SignedJWT.parse(provider.token());

    assertThat(jwt.verify(new MACVerifier(SECRET.getBytes(StandardCharsets.UTF_8)))).isTrue();
    assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(ServiceTokenProvider.SUBJECT);
    assertThat(jwt.getJWTClaimsSet().getStringListClaim("roles")).containsExactly("SERVICE");
    assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant())
        .isEqualTo(clock.now.plus(ServiceTokenProvider.TOKEN_TTL));
  }

  @Test
  void token_isReusedUntilRefreshMargin() {
    String first = provider.token();

    clock.now =
        clock.now.plus(ServiceTokenProvider.TOKEN_TTL).minus(ServiceTokenProvider.REFRESH_MARGIN);
    assertThat(provider.token()).isEqualTo(first);

    clock.now = clock.now.plusSeconds(1);
    assertThat(provider.token()).isNotEqualTo(first);
  }
}
