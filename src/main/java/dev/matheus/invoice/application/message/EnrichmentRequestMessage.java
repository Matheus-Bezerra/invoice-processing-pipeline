package dev.matheus.invoice.application.message;

import java.time.Instant;
import java.util.UUID;

/** Envelope da solicitação de enriquecimento (spec, seção 3.1). */
public record EnrichmentRequestMessage(
        UUID messageId,
        String eventType,
        Instant occurredAt,
        UUID correlationId,
        Payload payload) {

    public static final String EVENT_TYPE = "invoice.enrichment.requested";

    public record Payload(UUID invoiceId, String accessKey) {
    }

    public static EnrichmentRequestMessage of(UUID messageId, UUID invoiceId, String accessKey, Instant occurredAt) {
        return new EnrichmentRequestMessage(messageId, EVENT_TYPE, occurredAt, invoiceId,
                new Payload(invoiceId, accessKey));
    }
}
