package com.ledgerwatch.insightsservice.controller;

import com.ledgerwatch.insightsservice.ai.InsightsChat;
import com.ledgerwatch.insightsservice.dto.AskRequest;
import com.ledgerwatch.insightsservice.dto.AskResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/insights")
public class InsightsController {

  private final InsightsChat insightsChat;

  public InsightsController(InsightsChat insightsChat) {
    this.insightsChat = insightsChat;
  }

  /**
   * Answers a question about the caller's own accounts. Claude reads the ledger through the
   * insights tools as the caller, so it sees only what the caller's token may read.
   */
  @PostMapping("/ask")
  public AskResponse ask(@Valid @RequestBody AskRequest request, @AuthenticationPrincipal Jwt caller) {
    return new AskResponse(insightsChat.ask(request.question(), caller));
  }
}
