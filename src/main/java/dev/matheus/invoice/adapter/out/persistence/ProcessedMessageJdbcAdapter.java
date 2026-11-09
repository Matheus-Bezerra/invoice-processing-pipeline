package dev.matheus.invoice.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.matheus.invoice.application.port.out.ProcessedMessageStore;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
class ProcessedMessageJdbcAdapter implements ProcessedMessageStore {

    private final JdbcClient jdbc;

    @Override
    public boolean markProcessed(UUID messageId, Instant now) {
        return jdbc.sql("""
                INSERT INTO processed_messages (message_id, processed_at)
                VALUES (:messageId, :now)
                ON CONFLICT (message_id) DO NOTHING
                """)
                .param("messageId", messageId)
                .param("now", Timestamp.from(now))
                .update() == 1;
    }
}
