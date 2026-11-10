package dev.matheus.invoice.adapter.out.messaging;

import static dev.matheus.invoice.config.messaging.MessagingTopology.EXCHANGE;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_DLQ;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_QUEUE;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import dev.matheus.invoice.RabbitOnlyTestcontainersConfiguration;
import dev.matheus.invoice.application.port.out.EnrichmentResultPublisher;
import dev.matheus.invoice.application.port.out.ResultPublishFailedException;

/** Publisher do resultado com o RabbitMQ real, no perfil {@code webhook} (sem banco). */
@Import(RabbitOnlyTestcontainersConfiguration.class)
@ActiveProfiles("webhook")
@SpringBootTest(properties = "invoice.webhook.hmac-secret=segredo-de-teste")
class RabbitEnrichmentResultPublisherTest {

    @Autowired
    private EnrichmentResultPublisher publisher;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;

    @BeforeEach
    void purge() {
        rabbitAdmin.initialize();
        rabbitAdmin.purgeQueue(RESULT_QUEUE, false);
        rabbitAdmin.purgeQueue(RESULT_DLQ, false);
    }

    @Test
    void confirmedResultReachesTheResultQueueWithTheExactBodyAndTheEnvelopeProperties() {
        UUID messageId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        // Espaços e ordem dos campos propositais: o corpo assinado pelo provedor não pode ser reserializado.
        byte[] body = ("{ \"payload\" : {}, \"messageId\":\"" + messageId + "\" }").getBytes(StandardCharsets.UTF_8);

        publisher.publish(messageId, correlationId, body);

        Message received = rabbitTemplate.receive(RESULT_QUEUE, 5_000);
        assertThat(received).isNotNull();
        assertThat(received.getBody()).isEqualTo(body);
        assertThat(received.getMessageProperties().getMessageId()).isEqualTo(messageId.toString());
        assertThat(received.getMessageProperties().getCorrelationId()).isEqualTo(correlationId.toString());
        assertThat(received.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(received.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    }

    @Test
    void resultWithoutCorrelationIdIsStillPublished() {
        publisher.publish(UUID.randomUUID(), null, "{}".getBytes(StandardCharsets.UTF_8));

        Message received = rabbitTemplate.receive(RESULT_QUEUE, 5_000);
        assertThat(received).isNotNull();
        assertThat(received.getMessageProperties().getCorrelationId()).isNull();
    }

    @Test
    void unroutableResultIsAPublicationFailure() {
        rabbitAdmin.deleteQueue(RESULT_QUEUE);
        try {
            assertThatThrownBy(() -> publisher.publish(UUID.randomUUID(), UUID.randomUUID(), "{}".getBytes()))
                    .isInstanceOf(ResultPublishFailedException.class)
                    .hasMessageContaining("devolvida");
        } finally {
            rabbitAdmin.initialize();
        }
    }

    @Test
    void nackFromTheBrokerIsAPublicationFailure() {
        rabbitAdmin.deleteQueue(RESULT_QUEUE);
        Queue fullQueue = QueueBuilder.durable(RESULT_QUEUE)
                .maxLength(1)
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .build();
        rabbitAdmin.declareQueue(fullQueue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(fullQueue).to(new DirectExchange(EXCHANGE))
                .with(RESULT_ROUTING_KEY));
        try {
            publisher.publish(UUID.randomUUID(), UUID.randomUUID(), "{}".getBytes());

            assertThatThrownBy(() -> publisher.publish(UUID.randomUUID(), UUID.randomUUID(), "{}".getBytes()))
                    .isInstanceOf(ResultPublishFailedException.class)
                    .hasMessageContaining("nack");
        } finally {
            rabbitAdmin.deleteQueue(RESULT_QUEUE);
            rabbitAdmin.initialize();
        }
    }
}
