package com.digitalbank.customerservice;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@EnableFeignClients
@ComponentScan(basePackages = {
        "com.digitalbank.customerservice",
        "com.commons"
})
public class CusomerServiceApplication {

    public static void main(String[] args) {

        System.setProperty("user.timezone", "UTC");
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        System.out.println(
                "CURRENT TIMEZONE = "
                        + TimeZone.getDefault().getID());

        SpringApplication.run(
                CusomerServiceApplication.class,
                args);
    }

}