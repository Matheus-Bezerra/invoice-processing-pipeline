package dev.matheus.invoice.adapter.in.messaging;

import static dev.matheus.invoice.config.messaging.MessagingTopology.REQUEST_QUEUE;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import dev.matheus.invoice.application.message.EnrichmentRequestMessage;
import dev.matheus.invoice.application.port.out.provider.ProviderRejectedException;
import dev.matheus.invoice.application.service.provider.EnrichmentRequestForwardingService;
import dev.matheus.invoice.application.service.provider.ForwardEnrichmentRequestCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumer da fila de solicitação (só no worker). Mensagem malformada e recusa do provedor ({@code 4xx}) vão direto
 * para a DLQ ({@link AmqpRejectAndDontRequeueException}, sem retry). Qualquer outra exceção é erro técnico: o
 * container faz os retries e depois manda para a DLQ (ver {@code RequestConsumerConfig}). Sem exceção, o
 * {@code ack} sai depois da resposta {@code 202}. O worker não revalida a chave, já validada pela api.
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
class EnrichmentRequestConsumer {

    private final EnrichmentRequestForwardingService service;
    private final JsonMapper jsonMapper;

    @RabbitListener(queues = REQUEST_QUEUE, containerFactory = "requestListenerContainerFactory")
    void onRequest(Message message) {
        ForwardEnrichmentRequestCommand command = parse(message);
        try {
            service.forward(command);
        } catch (ProviderRejectedException e) {
            log.warn("Provedor recusou a solicitação, mensagem enviada para a DLQ: correlationId={}, messageId={}, status={}",
                    command.correlationId(), command.messageId(), e.status());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    private ForwardEnrichmentRequestCommand parse(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        EnrichmentRequestMessage request;
        try {
            request = jsonMapper.readValue(body, EnrichmentRequestMessage.class);
        } catch (JacksonException e) {
            throw malformed(null, readFailure(e));
        }
        if (request == null || request.messageId() == null) {
            throw malformed(request, "messageId ausente");
        }
        EnrichmentRequestMessage.Payload payload = request.payload();
        if (payload == null || payload.invoiceId() == null) {
            throw malformed(request, "payload.invoiceId ausente");
        }
        if (payload.accessKey() == null || payload.accessKey().isBlank()) {
            throw malformed(request, "payload.accessKey ausente");
        }
        UUID correlationId = request.correlationId() != null ? request.correlationId() : payload.invoiceId();
        return new ForwardEnrichmentRequestCommand(request.messageId(), correlationId, body);
    }

    /** Só o caminho do campo: a mensagem do Jackson traz o valor recebido, que não vai para o log. */
    private static String readFailure(JacksonException e) {
        String path = e.getPath().stream()
                .map(JacksonException.Reference::getPropertyName)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("."));
        return path.isEmpty() ? "JSON inválido" : "campo com tipo ou formato ilegível: " + path;
    }

    private static AmqpRejectAndDontRequeueException malformed(EnrichmentRequestMessage request, String reason) {
        log.warn("Solicitação malformada enviada para a DLQ: correlationId={}, messageId={}, motivo={}",
                request == null ? null : request.correlationId(), request == null ? null : request.messageId(), reason);
        return new AmqpRejectAndDontRequeueException("Solicitação malformada: " + reason);
    }
}
