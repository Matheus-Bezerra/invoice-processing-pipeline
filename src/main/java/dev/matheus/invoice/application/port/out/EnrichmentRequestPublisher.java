package dev.matheus.invoice.application.port.out;

import java.util.UUID;

public interface EnrichmentRequestPublisher {

    /**
     * Publica a solicitação e só retorna depois do <em>publisher confirm</em>. Lança exceção quando o broker
     * não confirma (nack, timeout, mensagem devolvida ou falha de conexão).
     */
    void publish(UUID messageId, UUID correlationId, String payloadJson);
}
