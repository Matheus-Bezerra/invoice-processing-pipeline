package dev.matheus.invoice.application.service;

import java.util.UUID;

/** Resultado já autenticado e validado no formato. {@code body} é o corpo exato recebido do provedor. */
public record PublishEnrichmentResultCommand(UUID messageId, UUID correlationId, byte[] body) {
}
