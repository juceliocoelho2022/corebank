package com.corebank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class CoreBankApplication {
    public static void main(String[] args) {
        SpringApplication.run(CoreBankApplication.class, args);
    }
}
