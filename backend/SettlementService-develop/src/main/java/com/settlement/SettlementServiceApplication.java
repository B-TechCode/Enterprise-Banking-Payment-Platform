package com.settlement;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SettlementServiceApplication {

    public static void main(String[] args) {
		// PostgreSQL rejects the JVM default timezone id "Asia/Calcutta".
		// Force UTC before the context starts so the JDBC driver negotiates a
		// timezone the server accepts and timestamps are stored consistently.
		System.setProperty("user.timezone", "UTC");
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        SpringApplication.run(SettlementServiceApplication.class, args);
    }
}
