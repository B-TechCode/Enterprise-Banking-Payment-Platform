package com.digitalbank.aicommerce.config;

import org.springframework.context.annotation.Configuration;

/**
 * Builds the Anthropic client used by the agent.
 *
 * <p>The API key is read from the ANTHROPIC_API_KEY environment variable and is
 * never held in a configuration file.</p>
 */
@Configuration
public class AnthropicClientConfig {

    // TODO: expose an AnthropicClient bean built from the environment.
}
