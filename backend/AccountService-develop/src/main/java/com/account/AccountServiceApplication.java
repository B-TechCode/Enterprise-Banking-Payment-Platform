package com.account;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;

import com.commons.security.DefaultSecurityConfig;
import com.commons.security.FeignTokenRelayConfig;

@Import({DefaultSecurityConfig.class, FeignTokenRelayConfig.class})
@SpringBootApplication(scanBasePackages = {"com.account", "com.commons"})
public class AccountServiceApplication {

	public static void main(String[] args) {
		// PostgreSQL rejects the JVM default timezone id "Asia/Calcutta".
		// Force UTC before the context starts so the JDBC driver negotiates a
		// timezone the server accepts and timestamps are stored consistently.
		System.setProperty("user.timezone", "UTC");
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

		SpringApplication.run(AccountServiceApplication.class, args);
	}

}
