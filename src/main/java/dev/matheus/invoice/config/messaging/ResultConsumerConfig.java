package dev.matheus.invoice.config.messaging;

import java.time.Duration;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import lombok.extern.slf4j.Slf4j;

/**
 * Container do consumer de resultado, declarado explicitamente: {@code prefetch = 1}, {@code ack} automático depois
 * do retorno do listener (portanto depois do commit) e retry sem estado dentro do worker. Rejeições
 * ({@link AmqpRejectAndDontRequeueException}: malformada ou chave inexistente) não fazem retry. Erros técnicos fazem
 * 3 retries (2s, 4s e 8s por padrão) e depois vão para a DLQ. Nada volta para a fila sozinho:
 * {@code defaultRequeueRejected = false}.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@Profile("worker")
class ResultConsumerConfig {

    @Bean
    SimpleRabbitListenerContainerFactory resultListenerContainerFactory(ConnectionFactory connectionFactory,
            @Value("${invoice.messaging.result-retry.max-retries}") int maxRetries,
            @Value("${invoice.messaging.result-retry.initial-interval}") Duration initialInterval,
            @Value("${invoice.messaging.result-retry.multiplier}") double multiplier,
            @Value("${invoice.messaging.result-retry.max-interval}") Duration maxInterval) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setPrefetchCount(1);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(retry(maxRetries, initialInterval, multiplier, maxInterval));
        return factory;
    }

    private static MethodInterceptor retry(int maxRetries, Duration initialInterval, double multiplier,
            Duration maxInterval) {
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
            log.error("Tentativas esgotadas, mensagem enviada para a DLQ: correlationId={}, messageId={}, erro={}",
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
