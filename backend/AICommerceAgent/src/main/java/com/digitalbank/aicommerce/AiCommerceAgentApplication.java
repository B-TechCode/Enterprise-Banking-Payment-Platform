package com.digitalbank.aicommerce;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;

/**
 * AI commerce agent.
 *
 * <p>Understands natural-language requests to check an account balance or pay a
 * bill, and serves them by calling the existing REST APIs of the platform as the
 * authenticated caller. It holds no financial state of its own and never opens a
 * connection to the database of another service.</p>
 *
 * <p>Anything that moves money is staged as a proposal and executed only after
 * the user explicitly confirms it through a separate request.</p>
 */
@SpringBootApplication
@ComponentScan(basePackages = { "com.digitalbank.aicommerce", "com.commons" })
@EnableFeignClients(basePackages = "com.digitalbank.aicommerce.client")
public class AiCommerceAgentApplication {

    public static void main(String[] args) {
        // PostgreSQL rejects the JVM default timezone id "Asia/Calcutta".
        // Force UTC before the context starts, matching the other services.
        System.setProperty("user.timezone", "UTC");
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        SpringApplication.run(AiCommerceAgentApplication.class, args);
    }
}
