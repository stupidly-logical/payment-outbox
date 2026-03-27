package com.paymentpipeline.relay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.paymentpipeline.relay",
        "com.paymentpipeline.outbox"        // picks up OutboxEventRepository
})
@EntityScan(basePackages = {
        "com.paymentpipeline.relay",
        "com.paymentpipeline.outbox"        // picks up OutboxEvent entity
})
@EnableJpaRepositories(basePackages = {
        "com.paymentpipeline.relay",
        "com.paymentpipeline.outbox"        // registers OutboxEventRepository as a bean
})
@EnableScheduling
public class OutboxRelayApplication {

    public static void main(String[] args) {
        SpringApplication.run(OutboxRelayApplication.class, args);
    }
}