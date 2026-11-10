package dev.matheus.invoice.config.messaging;

import static dev.matheus.invoice.config.messaging.MessagingTopology.DLX;
import static dev.matheus.invoice.config.messaging.MessagingTopology.EXCHANGE;
import static dev.matheus.invoice.config.messaging.MessagingTopology.REQUEST_DLQ;
import static dev.matheus.invoice.config.messaging.MessagingTopology.REQUEST_DLQ_ROUTING_KEY;
import static dev.matheus.invoice.config.messaging.MessagingTopology.REQUEST_QUEUE;
import static dev.matheus.invoice.config.messaging.MessagingTopology.REQUEST_ROUTING_KEY;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_DLQ;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_DLQ_ROUTING_KEY;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_QUEUE;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_ROUTING_KEY;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologia declarada nos três perfis (a declaração é idempotente). Como a publicação é {@code mandatory}, quem
 * publica precisa da fila de destino já ligada ao exchange: a api, da fila de solicitação, e o webhook, da de
 * resultado. O worker consome as duas filas e usa as duas DLQs.
 */
@Configuration(proxyBeanMethods = false)
class RabbitTopologyConfig {

    @Bean
    Declarables enrichmentTopology() {
        DirectExchange exchange = ExchangeBuilder.directExchange(EXCHANGE).durable(true).build();
        DirectExchange deadLetterExchange = ExchangeBuilder.directExchange(DLX).durable(true).build();

        Queue requestQueue = QueueBuilder.durable(REQUEST_QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(REQUEST_DLQ_ROUTING_KEY)
                .build();
        Queue requestDlq = QueueBuilder.durable(REQUEST_DLQ).build();
        Queue resultQueue = QueueBuilder.durable(RESULT_QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(RESULT_DLQ_ROUTING_KEY)
                .build();
        Queue resultDlq = QueueBuilder.durable(RESULT_DLQ).build();

        return new Declarables(
                exchange,
                deadLetterExchange,
                requestQueue,
                requestDlq,
                resultQueue,
                resultDlq,
                BindingBuilder.bind(requestQueue).to(exchange).with(REQUEST_ROUTING_KEY),
                BindingBuilder.bind(resultQueue).to(exchange).with(RESULT_ROUTING_KEY),
                BindingBuilder.bind(requestDlq).to(deadLetterExchange).with(REQUEST_DLQ_ROUTING_KEY),
                BindingBuilder.bind(resultDlq).to(deadLetterExchange).with(RESULT_DLQ_ROUTING_KEY));
    }
}
