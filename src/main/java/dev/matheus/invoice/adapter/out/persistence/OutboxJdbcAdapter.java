package dev.matheus.invoice.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.matheus.invoice.application.port.out.OutboxPort;
import dev.matheus.invoice.domain.AccessKey;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
class OutboxJdbcAdapter implements OutboxPort {

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
                .param("now", java.sql.Timestamp.from(now))
                .update();
    }
}
