package com.payments.orch;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.payments", "com.commons"})
@EnableFeignClients
@EnableKafka
@EnableScheduling
public class PaymentOrchestratorApplication {

	public static void main(String[] args) {
		// PostgreSQL rejects the JVM default timezone id "Asia/Calcutta".
		// Force UTC before the context starts so the JDBC driver negotiates a
		// timezone the server accepts and timestamps are stored consistently.
		System.setProperty("user.timezone", "UTC");
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

		SpringApplication.run(PaymentOrchestratorApplication.class, args);
	}

}

