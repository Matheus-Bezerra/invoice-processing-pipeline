package dev.matheus.invoice.application.port.out.persistence.outbox;

import java.util.UUID;

/** Linha do outbox reservada para publicação. O {@code id} é o {@code messageId} da solicitação. */
public record PendingRequest(UUID id, UUID invoiceId, String payloadJson, int attempts) {
}
