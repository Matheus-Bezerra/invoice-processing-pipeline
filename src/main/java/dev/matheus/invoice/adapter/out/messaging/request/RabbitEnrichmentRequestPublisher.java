package dev.matheus.invoice.adapter.out.messaging.request;

import dev.matheus.invoice.adapter.out.messaging.ConfirmedPublisher;
import dev.matheus.invoice.adapter.out.messaging.MessagePublishException;
import static dev.matheus.invoice.config.messaging.MessagingTopology.REQUEST_ROUTING_KEY;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import dev.matheus.invoice.application.port.out.messaging.EnrichmentRequestPublisher;
import dev.matheus.invoice.application.port.out.messaging.RequestPublishFailedException;

/** Publica a solicitação com <em>publisher confirm</em> (ver {@link ConfirmedPublisher}). */
@Component
@Profile("!webhook")
class RabbitEnrichmentRequestPublisher implements EnrichmentRequestPublisher {

    private final ConfirmedPublisher confirmedPublisher;

    RabbitEnrichmentRequestPublisher(RabbitTemplate rabbitTemplate,
            @Value("${invoice.messaging.confirm-timeout}") Duration confirmTimeout) {
        this.confirmedPublisher = new ConfirmedPublisher(rabbitTemplate, confirmTimeout);
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

        try {
            confirmedPublisher.send(REQUEST_ROUTING_KEY, message, messageId);
        } catch (MessagePublishException e) {
            throw new RequestPublishFailedException(e.getMessage(), e);
        }
    }
}
