package dev.matheus.invoice.application.service.provider;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import dev.matheus.invoice.application.port.out.ProviderClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Repassa a solicitação ao provedor. Não acessa o banco nem altera a nota: ela continua {@code IN_ANALYSIS} até o
 * resultado chegar pelo webhook.
 */
@Slf4j
@Service
@Profile("worker")
@RequiredArgsConstructor
public class EnrichmentRequestForwardingService {

    private final ProviderClient provider;

    public void forward(ForwardEnrichmentRequestCommand command) {
        provider.requestEnrichment(command.correlationId(), command.requestJson());
        log.info("Solicitação aceita pelo provedor: correlationId={}, messageId={}",
                command.correlationId(), command.messageId());
    }
}
