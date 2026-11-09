package dev.matheus.invoice.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.matheus.invoice.application.port.out.OutboxPort;
import dev.matheus.invoice.application.port.out.PendingRequest;
import dev.matheus.invoice.domain.AccessKey;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
class OutboxJdbcAdapter implements OutboxPort {

    private static final RowMapper<PendingRequest> PENDING_REQUEST = (rs, rowNum) -> new PendingRequest(
            rs.getObject("id", UUID.class),
            rs.getObject("invoice_id", UUID.class),
            rs.getString("payload"),
            rs.getInt("attempts"));

    private final JdbcClient jdbc;

    @Override
    public void enqueue(UUID id, UUID invoiceId, AccessKey accessKey, String payloadJson, Instant now) {
        jdbc.sql("""
                INSERT INTO outbox (id, invoice_id, access_key, payload, attempts, next_attempt_at, created_at)
                VALUES (:id, :invoiceId, :accessKey, CAST(:payload AS jsonb), 0, :now, :now)
                """)
                .param("id", id)
                .param("invoiceId", invoiceId)
                .param("accessKey", accessKey.value())
                .param("payload", payloadJson)
                .param("now", Timestamp.from(now))
                .update();
    }

    @Override
    public Optional<PendingRequest> lockById(UUID id) {
        return jdbc.sql("""
                SELECT id, invoice_id, payload::text AS payload, attempts
                FROM outbox
                WHERE id = :id
                FOR UPDATE SKIP LOCKED
                """)
                .param("id", id)
                .query(PENDING_REQUEST)
                .optional();
    }

    @Override
    public List<PendingRequest> lockDue(Instant now, int limit) {
        return jdbc.sql("""
                SELECT id, invoice_id, payload::text AS payload, attempts
                FROM outbox
                WHERE next_attempt_at <= :now
                ORDER BY next_attempt_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(PENDING_REQUEST)
                .list();
    }

    @Override
    public void delete(UUID id) {
        jdbc.sql("DELETE FROM outbox WHERE id = :id").param("id", id).update();
    }

    @Override
    public void markFailed(UUID id, int attempts, Instant nextAttemptAt, String error) {
        jdbc.sql("""
                UPDATE outbox
                SET attempts = :attempts, next_attempt_at = :nextAttemptAt, last_error = :error
                WHERE id = :id
                """)
                .param("id", id)
                .param("attempts", attempts)
                .param("nextAttemptAt", Timestamp.from(nextAttemptAt))
                .param("error", error)
                .update();
    }
}
