package dev.matheus.invoice.adapter.out.messaging;

import static dev.matheus.invoice.config.messaging.MessagingTopology.EXCHANGE;
import static dev.matheus.invoice.config.messaging.MessagingTopology.REQUEST_ROUTING_KEY;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import dev.matheus.invoice.application.port.out.EnrichmentRequestPublisher;

/**
 * Publica a solicitação com <em>publisher confirm</em> correlacionado e {@code mandatory}: só retorna com o
 * {@code ack} do broker e sem devolução (mensagem roteada para alguma fila).
 */
@Component
class RabbitEnrichmentRequestPublisher implements EnrichmentRequestPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    RabbitEnrichmentRequestPublisher(RabbitTemplate rabbitTemplate,
            @Value("${invoice.messaging.confirm-timeout}") Duration confirmTimeout) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public void publish(UUID messageId, UUID correlationId, String payloadJson) {
        // O JSON já gravado no outbox é publicado como está, para a mensagem ser idêntica em toda republicação.
        Message message = MessageBuilder.withBody(payloadJson.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setMessageId(messageId.toString())
                .setCorrelationId(correlationId.toString())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        CorrelationData correlation = new CorrelationData(messageId.toString());

        rabbitTemplate.send(EXCHANGE, REQUEST_ROUTING_KEY, message, correlation);

        CorrelationData.Confirm confirm = awaitConfirm(correlation);
        if (correlation.getReturned() != null) {
            throw new MessagePublishException("Mensagem devolvida pelo broker (sem fila para rotear): "
                    + correlation.getReturned().getReplyText());
        }
        if (!confirm.ack()) {
            throw new MessagePublishException("Broker recusou a mensagem (nack): " + confirm.reason());
        }
    }

    private CorrelationData.Confirm awaitConfirm(CorrelationData correlation) {
        try {
            return correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new MessagePublishException("Sem publisher confirm em " + confirmTimeout.toMillis() + " ms", e);
        } catch (ExecutionException e) {
            throw new MessagePublishException("Falha ao aguardar o publisher confirm", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessagePublishException("Interrompido aguardando o publisher confirm", e);
        }
    }
}
