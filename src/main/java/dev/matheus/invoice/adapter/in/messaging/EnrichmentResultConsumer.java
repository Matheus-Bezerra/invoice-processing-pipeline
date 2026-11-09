package dev.matheus.invoice.adapter.in.messaging;

import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_QUEUE;

import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import dev.matheus.invoice.application.service.EnrichmentResultCommand;
import dev.matheus.invoice.application.service.EnrichmentResultService;
import dev.matheus.invoice.application.service.UnknownAccessKeyException;
import dev.matheus.invoice.domain.AccessKey;
import dev.matheus.invoice.domain.EnrichmentData;
import dev.matheus.invoice.domain.exception.InvalidAccessKeyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumer da fila de resultado (só no worker). Mensagem malformada e chave inexistente vão direto para a DLQ
 * ({@link AmqpRejectAndDontRequeueException}, sem retry). Qualquer outra exceção é erro técnico: o container faz
 * os retries e depois manda para a DLQ (ver {@code ResultConsumerConfig}). Sem exceção, o {@code ack} sai depois
 * do commit.
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
class EnrichmentResultConsumer {

    private final EnrichmentResultService service;
    private final JsonMapper jsonMapper;

    @RabbitListener(queues = RESULT_QUEUE, containerFactory = "resultListenerContainerFactory")
    void onResult(Message message) {
        EnrichmentResultCommand command = parse(message);
        try {
            service.process(command);
        } catch (UnknownAccessKeyException e) {
            log.warn("Chave inexistente, mensagem enviada para a DLQ: correlationId={}, messageId={}",
                    command.correlationId(), command.messageId());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    private EnrichmentResultCommand parse(Message message) {
        EnrichmentResultMessage result;
        try {
            result = jsonMapper.readValue(message.getBody(), EnrichmentResultMessage.class);
        } catch (JacksonException e) {
            throw malformed(null, readFailure(e));
        }
        if (result == null || result.messageId() == null) {
            throw malformed(result, "messageId ausente");
        }
        EnrichmentResultMessage.Payload payload = result.payload();
        if (payload == null || payload.accessKey() == null) {
            throw malformed(result, "payload.accessKey ausente");
        }
        AccessKey accessKey;
        try {
            accessKey = AccessKey.of(payload.accessKey());
        } catch (InvalidAccessKeyException e) {
            throw malformed(result, "payload.accessKey fora do formato de 44 dígitos");
        }
        return new EnrichmentResultCommand(result.messageId(), result.correlationId(), accessKey,
                new EnrichmentData(payload.cnpj(), payload.amount(), payload.issueDate(), payload.cancelled()));
    }

    /** Só o caminho do campo: a mensagem do Jackson traz o valor recebido, que não vai para o log. */
    private static String readFailure(JacksonException e) {
        String path = e.getPath().stream()
                .map(JacksonException.Reference::getPropertyName)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("."));
        return path.isEmpty() ? "JSON inválido" : "campo com tipo ou formato ilegível: " + path;
    }

    private static AmqpRejectAndDontRequeueException malformed(EnrichmentResultMessage result, String reason) {
        log.warn("Mensagem malformada enviada para a DLQ: correlationId={}, messageId={}, motivo={}",
                result == null ? null : result.correlationId(), result == null ? null : result.messageId(), reason);
        return new AmqpRejectAndDontRequeueException("Mensagem malformada: " + reason);
    }
}
