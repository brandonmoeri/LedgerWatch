package com.ledgerwatch.insightsservice.config;

import com.ledgerwatch.insightsservice.ai.LedgerTools;
import com.ledgerwatch.insightsservice.ai.RecordingToolCallback;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatProperties;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicConnectionProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * The {@link ChatClient} every insights feature talks to Claude through.
 *
 * <p>Connection, model and token settings come from {@code spring.ai.anthropic.*} in
 * application.yml. The API key is read from {@code ANTHROPIC_API_KEY}, which is set from .env
 * locally and injected from Secrets Manager by the ECS task definition in AWS, so this class never
 * sees where it came from.
 */
@Configuration
public class ChatClientConfig {

  @Bean
  public ChatClient insightsChatClient(
      ChatClient.Builder builder,
      AnthropicConnectionProperties connection,
      @Value("classpath:prompts/insights-system.md") Resource systemPrompt,
      LedgerTools ledgerTools) {
    requireApiKey(connection.getApiKey());
    return builder
        .defaultSystem(systemPrompt)
        .defaultToolCallbacks(RecordingToolCallback.forTools(ledgerTools))
        .build();
  }

  /**
   * Spring AI binds a default temperature of 0.8 into the chat options, and Claude Opus 5.5 rejects
   * any sampling parameter with a 400. Runtime options can't unset it (they only override non-null
   * values), so clear it on the properties before the chat model is built from them.
   */
  @Bean
  static BeanPostProcessor clearAnthropicSamplingDefaults() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof AnthropicChatProperties properties) {
          properties.getOptions().setTemperature(null);
          properties.getOptions().setTopP(null);
          properties.getOptions().setTopK(null);
        }
        return bean;
      }
    };
  }

  /** Fails startup instead of the first request, which would otherwise get a 401. */
  static void requireApiKey(String apiKey) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException(
          "ANTHROPIC_API_KEY is not set. Locally, add it to .env; on ECS it comes from the "
              + "ledgerwatch/anthropic-api-key secret in Secrets Manager.");
    }
  }
}
