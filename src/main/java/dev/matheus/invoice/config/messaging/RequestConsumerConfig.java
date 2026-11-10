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
 * Container do consumer de solicitação: {@code prefetch = 1}, {@code ack} automático depois do retorno do listener
 * (portanto depois da resposta {@code 202} do provedor) e o mesmo retry do consumer de resultado (3 retries, 2s, 4s
 * e 8s por padrão) antes da DLQ da solicitação. Nada volta para a fila sozinho.
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
class RequestConsumerConfig {

    @Bean
    SimpleRabbitListenerContainerFactory requestListenerContainerFactory(ConnectionFactory connectionFactory,
            @Value("${invoice.messaging.request-retry.max-retries}") int maxRetries,
            @Value("${invoice.messaging.request-retry.initial-interval}") Duration initialInterval,
            @Value("${invoice.messaging.request-retry.multiplier}") double multiplier,
            @Value("${invoice.messaging.request-retry.max-interval}") Duration maxInterval) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setPrefetchCount(1);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(ListenerRetry.retry(maxRetries, initialInterval, multiplier, maxInterval));
        return factory;
    }
}
