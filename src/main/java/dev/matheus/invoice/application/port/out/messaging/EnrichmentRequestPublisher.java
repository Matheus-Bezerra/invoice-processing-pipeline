package dev.matheus.invoice.application.port.out.messaging;

import java.util.UUID;

public interface EnrichmentRequestPublisher {

    /**
     * Publica a solicitação e só retorna depois do <em>publisher confirm</em>.
     *
     * @throws RequestPublishFailedException o broker não confirmou (nack, timeout, devolução ou falha de conexão)
     */
    void publish(UUID messageId, UUID correlationId, String payloadJson);
}
