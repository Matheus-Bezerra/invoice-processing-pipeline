package dev.matheus.invoice.application.port.out.persistence.processedmessage;

import java.time.Instant;
import java.util.UUID;

/** Registro das mensagens de resultado já processadas. Participa da transação em andamento. */
public interface ProcessedMessageStore {

    /** Registra o {@code messageId}. Devolve {@code false} se ele já estava registrado (duplicata). */
    boolean markProcessed(UUID messageId, Instant now);
}
