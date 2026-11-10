package dev.matheus.invoice.adapter.out.messaging.result;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import dev.matheus.invoice.application.port.out.messaging.ResultPublishFailedException;

/**
 * Broker fora do ar (conexão recusada) também é falha de publicação: o webhook precisa responder {@code 503}, e não
 * {@code 500} (spec, seção 2.5). Achado no teste manual da fatia 6C, com o RabbitMQ parado.
 */
class RabbitEnrichmentResultPublisherBrokerDownTest {

    private CachingConnectionFactory connectionFactory;

    @AfterEach
    void close() {
        connectionFactory.destroy();
    }

    @Test
    void unreachableBrokerBecomesAPublishFailure() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        connectionFactory = new CachingConnectionFactory("localhost", closedPort);
        connectionFactory.setConnectionTimeout(500);
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMandatory(true);
        RabbitEnrichmentResultPublisher publisher =
                new RabbitEnrichmentResultPublisher(rabbitTemplate, Duration.ofSeconds(1));
        UUID messageId = UUID.randomUUID();

        assertThatThrownBy(() -> publisher.publish(messageId, UUID.randomUUID(),
                "{}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ResultPublishFailedException.class);
    }
}
