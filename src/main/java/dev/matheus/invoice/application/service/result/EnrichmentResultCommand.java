package dev.matheus.invoice.application.service.result;

import java.util.UUID;

import dev.matheus.invoice.domain.AccessKey;
import dev.matheus.invoice.domain.EnrichmentData;

/** Resultado do provedor já lido e validado no formato. O {@code correlationId} serve só para os logs. */
public record EnrichmentResultCommand(UUID messageId, UUID correlationId, AccessKey accessKey, EnrichmentData data) {
}
