package dev.matheus.invoice.config.messaging;

import java.time.Duration;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Container do consumer de resultado, declarado explicitamente: {@code prefetch = 1}, {@code ack} automático depois
 * do retorno do listener (portanto depois do commit) e retry sem estado dentro do worker. Rejeições
 * (AmqpRejectAndDontRequeueException: malformada ou chave inexistente) não fazem retry. Erros técnicos fazem
 * 3 retries (2s, 4s e 8s por padrão) e depois vão para a DLQ. Nada volta para a fila sozinho:
 * {@code defaultRequeueRejected = false}.
 */
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
        factory.setAdviceChain(ListenerRetry.retry(maxRetries, initialInterval, multiplier, maxInterval));
        return factory;
    }
}
