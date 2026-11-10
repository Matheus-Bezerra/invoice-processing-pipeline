package dev.matheus.invoice.application.service.provider;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import dev.matheus.invoice.application.port.out.messaging.EnrichmentResultPublisher;
import dev.matheus.invoice.application.port.out.messaging.ResultPublishFailedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Publica na fila o resultado recebido pelo webhook. Não acessa o banco nem aplica regra de negócio: aprovar ou
 * rejeitar a nota é do consumer do resultado.
 */
@Slf4j
@Service
@Profile("webhook")
@RequiredArgsConstructor
public class EnrichmentResultPublishingService {

    private final EnrichmentResultPublisher publisher;

    public void publish(PublishEnrichmentResultCommand command) {
        try {
            publisher.publish(command.messageId(), command.correlationId(), command.body());
        } catch (ResultPublishFailedException e) {
            log.error("Falha ao publicar o resultado, o provedor deve reenviar: correlationId={}, messageId={}, erro={}",
                    command.correlationId(), command.messageId(), e.getMessage());
            throw e;
        }
        log.info("Resultado do provedor publicado: correlationId={}, messageId={}",
                command.correlationId(), command.messageId());
    }
}
