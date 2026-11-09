package dev.matheus.invoice.adapter.in.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Envelope do resultado do provedor (spec, seção 3.2). {@code cancelled} é {@code Boolean}, nunca
 * {@code boolean}: com o primitivo, um campo ausente viraria {@code false} e aprovaria a nota por engano.
 */
record EnrichmentResultMessage(
        UUID messageId,
        String eventType,
        Instant occurredAt,
        UUID correlationId,
        Payload payload) {

    record Payload(String accessKey, String cnpj, BigDecimal amount, LocalDate issueDate, Boolean cancelled) {
    }
}
