package dev.matheus.invoice.application.port.out;

import java.util.UUID;

public interface EnrichmentResultPublisher {

    /**
     * Publica o resultado do provedor na fila de resultado, com o corpo exatamente como foi recebido, e só retorna
     * depois do <em>publisher confirm</em>.
     *
     * @throws ResultPublishFailedException o broker não confirmou (nack, timeout, devolução ou falha de conexão)
     */
    void publish(UUID messageId, UUID correlationId, byte[] body);
}
