package dev.matheus.invoice.application.service.provider;

import java.util.UUID;

/** Solicitação já lida e validada no formato. {@code requestJson} é o corpo exato recebido da fila. */
public record ForwardEnrichmentRequestCommand(UUID messageId, UUID correlationId, String requestJson) {
}
