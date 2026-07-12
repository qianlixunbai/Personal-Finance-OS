package com.financeos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.ZoneId;

@SpringBootApplication
public class FinanceOsApplication {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    public static void main(String[] args) {
        SpringApplication.run(FinanceOsApplication.class, args);
    }

    @Bean
    Clock businessClock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
