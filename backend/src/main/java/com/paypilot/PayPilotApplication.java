package com.paypilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PayPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayPilotApplication.class, args);
    }
}
