package dev.matheus.invoice.config.messaging;

import java.time.Duration;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;

import lombok.extern.slf4j.Slf4j;

/**
 * Container e retry dos consumers (resultado e solicitação), que só diferem nas propriedades de retry. Container:
 * {@code prefetch = 1}, {@code ack} automático depois do retorno do listener e {@code defaultRequeueRejected = false}
 * (nada volta para a fila sozinho). Retry sem estado: rejeições ({@link AmqpRejectAndDontRequeueException}) não fazem
 * retry, e o erro técnico faz {@code maxRetries} retries com espera crescente e depois vai para a DLQ.
 */
@Slf4j
final class ListenerRetry {

    private ListenerRetry() {
    }

    static SimpleRabbitListenerContainerFactory containerFactory(ConnectionFactory connectionFactory, int maxRetries,
            Duration initialInterval, double multiplier, Duration maxInterval) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setPrefetchCount(1);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(retry(maxRetries, initialInterval, multiplier, maxInterval));
        return factory;
    }

    private static MethodInterceptor retry(int maxRetries, Duration initialInterval, double multiplier, Duration maxInterval) {
        return RetryInterceptorBuilder.stateless()
                .maxRetries(maxRetries)
                .backOffOptions(initialInterval.toMillis(), multiplier, maxInterval.toMillis())
                .configureRetryPolicy(policy -> policy.predicate(error -> !isRejection(error)))
                .recoverer(sendToDlq())
                .build();
    }

    /** Depois do último retry (ou de uma rejeição), rejeita sem requeue: o broker manda para a DLQ. */
    private static MessageRecoverer sendToDlq() {
        return (message, cause) -> {
            if (isRejection(cause)) {
                throw new AmqpRejectAndDontRequeueException("Mensagem rejeitada sem retry", cause);
            }
            log.error("Tentativas esgotadas, mensagem enviada para a DLQ: fila={}, correlationId={}, messageId={}, erro={}",
                    message.getMessageProperties().getConsumerQueue(),
                    message.getMessageProperties().getCorrelationId(), message.getMessageProperties().getMessageId(),
                    cause.getMessage());
            throw new AmqpRejectAndDontRequeueException("Tentativas esgotadas", cause);
        };
    }

    /** O container embrulha a exceção do listener, então a cadeia de causas é percorrida. */
    private static boolean isRejection(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof AmqpRejectAndDontRequeueException) {
                return true;
            }
        }
        return false;
    }
}
