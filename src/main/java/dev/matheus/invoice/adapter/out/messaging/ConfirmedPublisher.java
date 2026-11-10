package dev.matheus.invoice.adapter.out.messaging;

import static dev.matheus.invoice.config.messaging.MessagingTopology.EXCHANGE;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Envio com <em>publisher confirm</em> correlacionado e {@code mandatory}: só retorna com o {@code ack} do broker e
 * sem devolução (mensagem roteada para alguma fila). Compartilhado pelos publishers da solicitação e do resultado.
 */
final class ConfirmedPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    ConfirmedPublisher(RabbitTemplate rabbitTemplate, Duration confirmTimeout) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = confirmTimeout;
    }

    void send(String routingKey, Message message, UUID messageId) {
        CorrelationData correlation = new CorrelationData(messageId.toString());

        rabbitTemplate.send(EXCHANGE, routingKey, message, correlation);

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
