package dev.matheus.invoice.application.service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.matheus.invoice.application.port.out.ClockPort;
import dev.matheus.invoice.application.port.out.EnrichmentRequestPublisher;
import dev.matheus.invoice.application.port.out.OutboxPort;
import dev.matheus.invoice.application.port.out.PendingRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Publica as solicitações pendentes do outbox. A publicação imediata (api) e o relay (worker) passam pela mesma
 * função: reserva a linha com {@code FOR UPDATE SKIP LOCKED}, publica com <em>publisher confirm</em> e apaga a
 * linha antes do commit. Em caso de falha, agenda a próxima tentativa com backoff, sem limite de tentativas.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisherService {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final OutboxPort outbox;
    private final EnrichmentRequestPublisher publisher;
    private final ClockPort clock;
    private final OutboxRelaySettings settings;

    /**
     * Publicação imediata de uma solicitação. Roda numa transação nova porque é chamada no {@code afterCommit} da
     * transação que gravou a nota. Devolve {@code false} se a linha já foi reservada ou publicada por outro.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean publish(UUID id) {
        return outbox.lockById(id).map(this::publishLocked).orElse(false);
    }

    /** Relay: publica um lote de solicitações vencidas. Devolve quantas foram publicadas. */
    @Transactional
    public int publishDue() {
        List<PendingRequest> due = outbox.lockDue(clock.now(), settings.batchSize());
        int published = 0;
        for (PendingRequest request : due) {
            if (publishLocked(request)) {
                published++;
            }
        }
        return published;
    }

    private boolean publishLocked(PendingRequest request) {
        try {
            publisher.publish(request.id(), request.invoiceId(), request.payloadJson());
        } catch (RuntimeException e) {
            int attempts = request.attempts() + 1;
            Duration delay = backoff(attempts);
            outbox.markFailed(request.id(), attempts, clock.now().plus(delay), describe(e));
            log.warn("Falha ao publicar a solicitação, nova tentativa em {}s: correlationId={}, messageId={}, tentativa={}, erro={}",
                    delay.toSeconds(), request.invoiceId(), request.id(), attempts, e.getMessage());
            return false;
        }
        outbox.delete(request.id());
        log.info("Solicitação de enriquecimento publicada: correlationId={}, messageId={}",
                request.invoiceId(), request.id());
        return true;
    }

    /** {@code min(2^attempts s, teto)}, com {@code attempts} já incrementado: 2s, 4s, 8s, ... até o teto. */
    Duration backoff(int attempts) {
        if (attempts >= 31) {
            return settings.maxBackoff();
        }
        Duration exponential = Duration.ofSeconds(1L << attempts);
        return exponential.compareTo(settings.maxBackoff()) < 0 ? exponential : settings.maxBackoff();
    }

    private static String describe(RuntimeException e) {
        String text = e.getClass().getSimpleName() + ": " + e.getMessage();
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
    }
}
