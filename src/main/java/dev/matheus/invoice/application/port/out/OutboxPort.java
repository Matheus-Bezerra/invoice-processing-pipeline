package dev.matheus.invoice.application.port.out;

import java.time.Instant;
import java.util.UUID;

import dev.matheus.invoice.domain.AccessKey;

public interface OutboxPort {

    /** Grava uma solicitação pendente de publicação. Deve participar da transação em andamento. */
    void enqueue(UUID id, UUID invoiceId, AccessKey accessKey, String payloadJson, Instant now);
}
