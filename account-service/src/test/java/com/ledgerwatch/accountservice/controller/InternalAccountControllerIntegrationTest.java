package com.ledgerwatch.accountservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ledgerwatch.accountservice.TestcontainersConfiguration;
import com.ledgerwatch.accountservice.domain.Account;
import com.ledgerwatch.accountservice.domain.AccountStatus;
import com.ledgerwatch.accountservice.repository.AccountRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SuppressWarnings("null")
public class InternalAccountControllerIntegrationTest {

  private static final RequestPostProcessor SERVICE =
      jwt().authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));

  @Autowired MockMvc mockMvc;
  @Autowired AccountRepository accountRepository;

  @AfterEach
  void cleanUp() {
    accountRepository.deleteAll();
  }

  private UUID seed(String balance, AccountStatus status) {
    Account account = new Account();
    account.setOwnerName("Seed");
    account.setBalance(new BigDecimal(balance));
    account.setStatus(status);
    return accountRepository.save(account).getId();
  }

  private ResultActions adjust(Object id, String json, RequestPostProcessor auth) throws Exception {
    return mockMvc.perform(
        post("/internal/accounts/{id}/balance-adjustments", id)
            .with(auth)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private BigDecimal balanceOf(UUID id) {
    return accountRepository.findById(id).orElseThrow().getBalance();
  }

  @Test
  void positiveDelta_returns200AndPersistsNewBalance() throws Exception {
    var id = seed("100.00", AccountStatus.ACTIVE);

    adjust(id, "{\"delta\": 25.1234}", SERVICE)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.balance").value(125.1234));

    assertThat(balanceOf(id)).isEqualByComparingTo("125.1234");
  }

  @Test
  void negativeDelta_reducesBalance() throws Exception {
    var id = seed("100.00", AccountStatus.ACTIVE);

    adjust(id, "{\"delta\": -40}", SERVICE).andExpect(status().isOk());

    assertThat(balanceOf(id)).isEqualByComparingTo("60");
  }

  @Test
  void debitExceedingBalance_returns422ProblemAndBalanceUnchanged() throws Exception {
    var id = seed("100.00", AccountStatus.ACTIVE);

    adjust(id, "{\"delta\": -100.01}", SERVICE)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:ledgerwatch:problem:insufficient-funds"))
        .andExpect(jsonPath("$.title").value("Insufficient funds"))
        .andExpect(jsonPath("$.status").value(422))
        .andExpect(jsonPath("$.accountId").value(id.toString()))
        .andExpect(jsonPath("$.amount").value(100.01));

    assertThat(balanceOf(id)).isEqualByComparingTo("100.00");
  }

  @Test
  void debitOfEntireBalance_returns200() throws Exception {
    var id = seed("100.00", AccountStatus.ACTIVE);

    adjust(id, "{\"delta\": -100}", SERVICE).andExpect(status().isOk());

    assertThat(balanceOf(id)).isEqualByComparingTo("0");
  }

  @Test
  void successiveDeltas_incrementVersion() throws Exception {
    var id = seed("0", AccountStatus.ACTIVE);
    long before = accountRepository.findById(id).orElseThrow().getVersion();

    adjust(id, "{\"delta\": 1}", SERVICE).andExpect(status().isOk());
    adjust(id, "{\"delta\": 1}", SERVICE).andExpect(status().isOk());

    var after = accountRepository.findById(id).orElseThrow();
    assertThat(after.getVersion()).isEqualTo(before + 2);
    assertThat(after.getBalance()).isEqualByComparingTo("2");
  }

  @Test
  void frozenAccount_returns409AndBalanceUnchanged() throws Exception {
    var id = seed("100.00", AccountStatus.FROZEN);

    adjust(id, "{\"delta\": 10}", SERVICE)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value(containsString("FROZEN")));

    assertThat(balanceOf(id)).isEqualByComparingTo("100.00");
  }

  @Test
  void closedAccount_returns409AndBalanceUnchanged() throws Exception {
    var id = seed("100.00", AccountStatus.CLOSED);

    adjust(id, "{\"delta\": 10}", SERVICE)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value(containsString("CLOSED")));

    assertThat(balanceOf(id)).isEqualByComparingTo("100.00");
  }

  @Test
  void unknownAccount_returns404() throws Exception {
    adjust(UUID.randomUUID(), "{\"delta\": 10}", SERVICE).andExpect(status().isNotFound());
  }

  @Test
  void missingDelta_returns400() throws Exception {
    var id = seed("0", AccountStatus.ACTIVE);

    adjust(id, "{}", SERVICE)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.delta").exists());
  }

  @Test
  void zeroDelta_returns400() throws Exception {
    var id = seed("0", AccountStatus.ACTIVE);

    adjust(id, "{\"delta\": 0}", SERVICE).andExpect(status().isBadRequest());
  }

  @Test
  void deltaWithMoreThanFourDecimals_returns400() throws Exception {
    var id = seed("0", AccountStatus.ACTIVE);

    adjust(id, "{\"delta\": 1.00001}", SERVICE)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.delta").exists());
  }

  @Test
  void adminRole_returns403() throws Exception {
    var id = seed("0", AccountStatus.ACTIVE);

    adjust(id, "{\"delta\": 1}", jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
        .andExpect(status().isForbidden());
  }

  @Test
  void analystRole_returns403() throws Exception {
    var id = seed("0", AccountStatus.ACTIVE);

    adjust(id, "{\"delta\": 1}", jwt().authorities(new SimpleGrantedAuthority("ROLE_ANALYST")))
        .andExpect(status().isForbidden());
  }

  @Test
  void noToken_returns401() throws Exception {
    mockMvc
        .perform(
            post("/internal/accounts/{id}/balance-adjustments", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"delta\": 1}"))
        .andExpect(status().isUnauthorized());
  }
}
