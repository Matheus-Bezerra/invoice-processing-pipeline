package dev.matheus.provider.model;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Envelope {@code EnrichmentResult} (spec, seção 3.2) enviado como corpo do callback. O simulador só gera resultados
 * completos e aprováveis; as rejeições são cobertas pelos testes automatizados do projeto principal.
 */
public record EnrichmentResult(UUID messageId, String eventType, Instant occurredAt, UUID correlationId, Payload payload) {

    static final String EVENT_TYPE = "invoice.enrichment.completed";

    /** CNPJ alfanumérico válido, o exemplo da spec (dígitos verificadores conferidos na fatia 2). */
    static final String VALID_CNPJ = "12ABC34501DE35";
    static final BigDecimal AMOUNT = new BigDecimal("1234.56");

    public record Payload(String accessKey, String cnpj, BigDecimal amount, LocalDate issueDate, Boolean cancelled) {
    }

    /**
     * O {@code messageId} é derivado do {@code messageId} da solicitação: a mesma solicitação gera sempre o mesmo
     * resultado, e o consumer descarta a duplicata.
     */
    public static EnrichmentResult approvableFor(EnrichmentRequest request, Instant now) {
        UUID messageId = UUID.nameUUIDFromBytes(
                ("result:" + request.messageId()).getBytes(StandardCharsets.UTF_8));
        Payload payload = new Payload(request.payload().accessKey(), VALID_CNPJ, AMOUNT,
                LocalDate.ofInstant(now, ZoneOffset.UTC), false);
        return new EnrichmentResult(messageId, EVENT_TYPE, now, request.effectiveCorrelationId(), payload);
    }
}
