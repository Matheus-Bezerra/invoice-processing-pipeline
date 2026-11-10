package dev.matheus.invoice.config;

import java.time.Duration;

import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.matheus.invoice.application.service.OutboxRelaySettings;

@Configuration(proxyBeanMethods = false)
@Profile("!webhook")
class OutboxConfig {

    @Bean
    OutboxRelaySettings outboxRelaySettings(
            @Value("${invoice.outbox.relay.batch-size}") int batchSize,
            @Value("${invoice.outbox.relay.max-backoff}") Duration maxBackoff) {
        return new OutboxRelaySettings(batchSize, maxBackoff);
    }
}
