package dev.matheus.invoice.integration.result;

import static dev.matheus.invoice.config.messaging.MessagingTopology.EXCHANGE;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_DLQ;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_QUEUE;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;

import dev.matheus.invoice.integration.support.IntegrationTestSupport;

/**
 * T7 em nível I, <b>variante 1</b>: reentrega provocada pelo broker. Um consumidor de teste com {@code ack} manual
 * recebe o resultado e fecha o canal <b>sem confirmar</b>; o RabbitMQ devolve a mensagem à fila e a entrega de novo
 * com {@code redelivered = true}. Prova que a reentrega vem do broker (e não de chamar a função duas vezes).
 * Perfil padrão: declara a topologia, mas não tem nenhum consumer real competindo pela fila.
 */
@SpringBootTest
class BrokerRedeliveryIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory;

    @BeforeEach
    void setUp() {
        rabbitAdmin.purgeQueue(RESULT_QUEUE, false);
        rabbitAdmin.purgeQueue(RESULT_DLQ, false);
    }

    @Test
    void t7_brokerRedeliversAMessageWhoseChannelClosedWithoutAck() throws Exception {
        String body = approvableResultJson(UUID.randomUUID(), UUID.randomUUID(), KEY);
        rabbitTemplate.send(EXCHANGE, RESULT_ROUTING_KEY, MessageBuilder
                .withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build());

        try (Connection connection = ((CachingConnectionFactory) connectionFactory).getRabbitConnectionFactory()
                .newConnection("teste-de-reentrega")) {
            // 1ª entrega: o consumidor de teste recebe a mensagem e fecha o canal sem ack.
            Channel firstChannel = connection.createChannel();
            GetResponse first = awaitGet(firstChannel);
            assertThat(first.getEnvelope().isRedeliver()).as("1ª entrega").isFalse();
            assertThat(new String(first.getBody(), StandardCharsets.UTF_8)).isEqualTo(body);
            firstChannel.close();

            // 2ª entrega: o broker devolveu a mensagem à fila e a marca como reentregue.
            Channel secondChannel = connection.createChannel();
            GetResponse second = awaitGet(secondChannel);
            assertThat(second.getEnvelope().isRedeliver()).as("2ª entrega").isTrue();
            assertThat(new String(second.getBody(), StandardCharsets.UTF_8)).isEqualTo(body);
            secondChannel.basicAck(second.getEnvelope().getDeliveryTag(), false);
        }

        await().atMost(WAIT).until(() -> rabbitAdmin.getQueueInfo(RESULT_QUEUE).getMessageCount() == 0);
        Message dead = rabbitTemplate.receive(RESULT_DLQ, 500);
        assertThat(dead).as("DLQ vazia").isNull();
    }

    private static GetResponse awaitGet(Channel channel) {
        AtomicReference<GetResponse> response = new AtomicReference<>();
        await().atMost(WAIT).until(() -> {
            response.set(channel.basicGet(RESULT_QUEUE, false));
            return response.get() != null;
        });
        return response.get();
    }
}
