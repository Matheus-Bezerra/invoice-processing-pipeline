package dev.matheus.invoice.adapter.out.messaging;

import static dev.matheus.invoice.config.messaging.MessagingTopology.EXCHANGE;
import static dev.matheus.invoice.config.messaging.MessagingTopology.REQUEST_DLQ;
import static dev.matheus.invoice.config.messaging.MessagingTopology.REQUEST_QUEUE;
import static dev.matheus.invoice.config.messaging.MessagingTopology.REQUEST_ROUTING_KEY;
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

import com.rabbitmq.client.GetResponse;

import dev.matheus.invoice.TestcontainersConfiguration;
import dev.matheus.invoice.application.port.out.EnrichmentRequestPublisher;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RabbitEnrichmentRequestPublisherTest {

    @Autowired
    private EnrichmentRequestPublisher publisher;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;

    @BeforeEach
    void purgeQueues() {
        rabbitAdmin.initialize();
        rabbitAdmin.purgeQueue(REQUEST_QUEUE, false);
        rabbitAdmin.purgeQueue(RESULT_QUEUE, false);
        rabbitAdmin.purgeQueue(RESULT_DLQ, false);
    }

    @Test
    void topologyIsDeclared() {
        assertThat(rabbitAdmin.getQueueProperties(REQUEST_QUEUE)).isNotNull();
        assertThat(rabbitAdmin.getQueueProperties(REQUEST_DLQ)).isNotNull();
        assertThat(rabbitAdmin.getQueueProperties(RESULT_QUEUE)).isNotNull();
        assertThat(rabbitAdmin.getQueueProperties(RESULT_DLQ)).isNotNull();
    }

    @Test
    void confirmedMessageReachesTheRequestQueueWithTheEnvelopeProperties() {
        UUID messageId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        String json = "{\"messageId\":\"" + messageId + "\"}";

        publisher.publish(messageId, invoiceId, json);

        Message received = rabbitTemplate.receive(REQUEST_QUEUE, 5_000);
        assertThat(received).isNotNull();
        assertThat(new String(received.getBody(), StandardCharsets.UTF_8)).isEqualTo(json);
        assertThat(received.getMessageProperties().getMessageId()).isEqualTo(messageId.toString());
        assertThat(received.getMessageProperties().getCorrelationId()).isEqualTo(invoiceId.toString());
        assertThat(received.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(received.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    }

    @Test
    void unroutableMessageIsAFailure() {
        rabbitAdmin.deleteQueue(REQUEST_QUEUE);
        try {
            assertThatThrownBy(() -> publisher.publish(UUID.randomUUID(), UUID.randomUUID(), "{}"))
                    .isInstanceOf(MessagePublishException.class)
                    .hasMessageContaining("devolvida");
        } finally {
            rabbitAdmin.initialize();
        }
    }

    @Test
    void nackFromTheBrokerIsAFailure() {
        // Fila cheia com overflow reject-publish: o broker recusa (nack) a segunda mensagem.
        rabbitAdmin.deleteQueue(REQUEST_QUEUE);
        Queue fullQueue = QueueBuilder.durable(REQUEST_QUEUE)
                .maxLength(1)
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .build();
        rabbitAdmin.declareQueue(fullQueue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(fullQueue).to(new DirectExchange(EXCHANGE))
                .with(REQUEST_ROUTING_KEY));
        try {
            publisher.publish(UUID.randomUUID(), UUID.randomUUID(), "{}");

            assertThatThrownBy(() -> publisher.publish(UUID.randomUUID(), UUID.randomUUID(), "{}"))
                    .isInstanceOf(MessagePublishException.class)
                    .hasMessageContaining("nack");
        } finally {
            rabbitAdmin.deleteQueue(REQUEST_QUEUE);
            rabbitAdmin.initialize();
        }
    }

    @Test
    void rejectedResultGoesToTheDeadLetterQueue() {
        rabbitTemplate.convertAndSend(EXCHANGE, RESULT_ROUTING_KEY, "resultado qualquer");

        rabbitTemplate.execute(channel -> {
            GetResponse response = null;
            for (int i = 0; i < 50 && response == null; i++) {
                response = channel.basicGet(RESULT_QUEUE, false);
                if (response == null) {
                    Thread.sleep(100);
                }
            }
            assertThat(response).isNotNull();
            channel.basicReject(response.getEnvelope().getDeliveryTag(), false);
            return null;
        });

        Message dead = rabbitTemplate.receive(RESULT_DLQ, 5_000);
        assertThat(dead).isNotNull();
        assertThat(dead.getMessageProperties().getHeaders()).containsKey("x-death");
    }
}
