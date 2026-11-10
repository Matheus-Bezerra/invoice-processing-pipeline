package dev.matheus.invoice.application.port.out.provider;

import java.util.UUID;

public interface ProviderClient {

    /**
     * Repassa a solicitação ao provedor e só retorna com a resposta {@code 202}. O corpo vai sem alterações.
     *
     * @throws ProviderRejectedException    o provedor recusou a solicitação (erro permanente, sem retry)
     * @throws ProviderUnavailableException erro técnico: {@code 5xx}, timeout ou conexão recusada
     */
    void requestEnrichment(UUID correlationId, String requestJson);
}
