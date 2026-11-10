package dev.matheus.invoice.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.matheus.invoice.domain.AccessKey;

/** Todas as operações participam da transação em andamento. As reservas usam {@code FOR UPDATE SKIP LOCKED}. */
public interface OutboxPort {

    /** Grava uma solicitação pendente de publicação. */
    void enqueue(UUID id, UUID invoiceId, AccessKey accessKey, String payloadJson, Instant now);

    /** Reserva a linha, se ela ainda existir e não estiver travada por outra transação. */
    Optional<PendingRequest> lockById(UUID id);

    /** Reserva até {@code limit} linhas com {@code next_attempt_at <= now} que não estejam travadas. */
    List<PendingRequest> lockDue(Instant now, int limit);

    void delete(UUID id);

    void markFailed(UUID id, int attempts, Instant nextAttemptAt, String error);
}
