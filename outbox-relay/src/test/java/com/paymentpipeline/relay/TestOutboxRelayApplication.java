package com.paymentpipeline.relay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Test bootstrap class for the outbox-relay module.
 *
 * Uses @SpringBootConfiguration + @EnableAutoConfiguration + @ComponentScan rather
 * than @SpringBootApplication so that OutboxRelayApplication (which is in the same
 * package and would otherwise be discovered by the scan) can be explicitly excluded.
 * Without the exclusion, both classes register @EnableJpaRepositories for the same
 * packages, causing a BeanDefinitionOverrideException.
 *
 * Also deliberately omits @EnableScheduling so the @Scheduled poller does NOT fire
 * during tests — tests call OutboxPoller.poll() directly for full determinism.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = {
                "com.paymentpipeline.relay",
                "com.paymentpipeline.outbox"
        },
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = OutboxRelayApplication.class
        )
)
@EntityScan(basePackages = {
        "com.paymentpipeline.relay",
        "com.paymentpipeline.outbox"
})
@EnableJpaRepositories(basePackages = {
        "com.paymentpipeline.relay",
        "com.paymentpipeline.outbox"
})
public class TestOutboxRelayApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestOutboxRelayApplication.class, args);
    }
}
