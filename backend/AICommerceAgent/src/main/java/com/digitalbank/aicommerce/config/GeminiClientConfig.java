package com.digitalbank.aicommerce.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the HTTP client used to reach the Gemini API.
 *
 * <p>The API key is read from the GEMINI_API_KEY environment variable and is
 * never held in a configuration file. It is bound into a default header here, so
 * no call site has to handle it and it cannot be logged as a URL parameter.</p>
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class GeminiClientConfig {

    static final String API_KEY_ENV = "GEMINI_API_KEY";

    /**
     * Reading the key here rather than through configuration binding keeps it out
     * of the Spring Environment, where an actuator endpoint could expose it.
     */
    @Bean
    public RestClient geminiRestClient(AgentProperties properties) {

        String apiKey = System.getenv(API_KEY_ENV);

        if (apiKey == null || apiKey.isBlank()) {
            // Fail at startup rather than on the first user request, so a
            // misconfigured deployment never reaches a customer.
            throw new IllegalStateException(
                    API_KEY_ENV + " is not set. The agent cannot start without it.");
        }

        Duration timeout = Duration.ofSeconds(properties.getRequestTimeoutSeconds());

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) timeout.toMillis());
        requestFactory.setReadTimeout((int) timeout.toMillis());

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("x-goog-api-key", apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
