package dev.matheus.provider.model;

import java.util.UUID;

/**
 * Solicitação recebida do {@code worker} (spec, seções 3.1 e 3.4). Só os campos que o simulador usa; os demais
 * ({@code eventType}, {@code occurredAt}) são ignorados.
 */
public record EnrichmentRequest(UUID messageId, UUID correlationId, Payload payload) {

    public record Payload(UUID invoiceId, String accessKey) {
    }

    /** O {@code correlationId} é opcional na solicitação; sem ele vale o {@code invoiceId}, como no worker. */
    public UUID effectiveCorrelationId() {
        return correlationId != null ? correlationId : payload.invoiceId();
    }
}
