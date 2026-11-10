package dev.matheus.invoice.adapter.in.scheduler;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.matheus.invoice.application.service.outbox.OutboxPublisherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Relay do outbox: só no worker, a cada tick (padrão 5s). */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
class OutboxRelayJob {

    private final OutboxPublisherService outboxPublisher;

    @Scheduled(fixedDelayString = "${invoice.outbox.relay.tick}")
    void relay() {
        try {
            int published = outboxPublisher.publishDue();
            if (published > 0) {
                log.info("Relay do outbox publicou {} solicitação(ões)", published);
            }
        } catch (RuntimeException e) {
            // Ex.: banco fora do ar. O próximo tick tenta de novo.
            log.error("Falha no relay do outbox", e);
        }
    }
}
