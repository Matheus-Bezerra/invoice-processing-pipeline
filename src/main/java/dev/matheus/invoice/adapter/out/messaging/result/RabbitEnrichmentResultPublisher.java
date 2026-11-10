package dev.matheus.invoice.adapter.out.messaging.result;

import dev.matheus.invoice.adapter.out.messaging.ConfirmedPublisher;
import dev.matheus.invoice.adapter.out.messaging.MessagePublishException;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_ROUTING_KEY;

import java.time.Duration;
import java.util.UUID;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageBuilderSupport;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import dev.matheus.invoice.application.port.out.messaging.EnrichmentResultPublisher;
import dev.matheus.invoice.application.port.out.messaging.ResultPublishFailedException;

/**
 * Publica o resultado do provedor na fila de resultado, com o corpo como chegou (sem reserializar) e
 * <em>publisher confirm</em>. Só existe no perfil {@code webhook}.
 */
@Component
@Profile("webhook")
class RabbitEnrichmentResultPublisher implements EnrichmentResultPublisher {

    private final ConfirmedPublisher confirmedPublisher;

    RabbitEnrichmentResultPublisher(RabbitTemplate rabbitTemplate,
            @Value("${invoice.messaging.confirm-timeout}") Duration confirmTimeout) {
        this.confirmedPublisher = new ConfirmedPublisher(rabbitTemplate, confirmTimeout);
    }

    @Override
    public void publish(UUID messageId, UUID correlationId, byte[] body) {
        MessageBuilderSupport<Message> builder = MessageBuilder.withBody(body)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(messageId.toString())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        if (correlationId != null) {
            builder.setCorrelationId(correlationId.toString());
        }
        Message message = builder.build();

        try {
            confirmedPublisher.send(RESULT_ROUTING_KEY, message, messageId);
        } catch (MessagePublishException e) {
            throw new ResultPublishFailedException(e.getMessage(), e);
        }
    }
}
