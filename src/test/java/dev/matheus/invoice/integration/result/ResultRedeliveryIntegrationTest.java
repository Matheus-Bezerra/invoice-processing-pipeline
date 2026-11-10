package dev.matheus.invoice.integration.result;

import static dev.matheus.invoice.config.messaging.MessagingTopology.EXCHANGE;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_DLQ;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_QUEUE;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import dev.matheus.invoice.integration.support.IntegrationTestSupport;

/**
 * T7 em nível I, <b>variante 2</b>: o consumer de resultado <b>real</b> recebe a reentrega provocada pelo broker.
 *
 * <p>Um interceptador só de testes é colocado <b>fora</b> do retry do consumer (primeiro da cadeia de advices). Ele
 * deixa o consumer real processar e gravar normalmente na 1ª entrega e, depois do commit, lança
 * {@link ImmediateRequeueAmqpException}: o container faz {@code nack} com requeue e o RabbitMQ reentrega a mensagem
 * com {@code redelivered = true}. Se o interceptador ficasse <i>dentro</i> do retry, o retry reexecutaria o
 * processamento em vez de devolver a mensagem ao broker. Na 2ª entrega o consumer vê o {@code messageId} em
 * {@code processed_messages}, não altera nada e dá {@code ack}.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles({"api", "worker"})
@Import(ResultRedeliveryIntegrationTest.RequeueAfterCommit.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "invoice.outbox.relay.tick=1h")
class ResultRedeliveryIntegrationTest extends IntegrationTestSupport {

    /** Um item por entrega: a flag {@code redelivered} que o broker mandou. */
    static final List<Boolean> deliveries = new CopyOnWriteArrayList<>();
    /** Um item por entrega: {@code approved_at} da nota logo depois do processamento da entrega. */
    static final List<Timestamp> approvedAtAfterEachDelivery = new CopyOnWriteArrayList<>();
    static volatile java.util.function.Supplier<Timestamp> approvedAtProbe = () -> null;

    @TestConfiguration(proxyBeanMethods = false)
    static class RequeueAfterCommit {

        @Bean
        static BeanPostProcessor requeueAfterCommitPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if ("resultListenerContainerFactory".equals(beanName)
                            && bean instanceof SimpleRabbitListenerContainerFactory factory) {
                        List<Advice> chain = new ArrayList<>();
                        chain.add(requeueFirstDeliveryAfterCommit());
                        chain.addAll(Arrays.asList(factory.getAdviceChain()));
                        factory.setAdviceChain(chain.toArray(Advice[]::new));
                    }
                    return bean;
                }
            };
        }

        private static MethodInterceptor requeueFirstDeliveryAfterCommit() {
            return invocation -> {
                // Argumentos de invokeListener(Channel, Object): o 2º é a Message.
                Message message = (Message) invocation.getArguments()[1];
                boolean redelivered = message.getMessageProperties().isRedelivered();
                Object result = invocation.proceed(); // retry + consumer real: processa e dá commit
                deliveries.add(redelivered);
                approvedAtAfterEachDelivery.add(approvedAtProbe.get());
                if (!redelivered) {
                    throw new ImmediateRequeueAmqpException("Reentrega provocada pelo teste depois do commit");
                }
                return result;
            };
        }
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID invoiceId;

    @BeforeEach
    void setUp() {
        rabbitAdmin.purgeQueue(RESULT_QUEUE, false);
        rabbitAdmin.purgeQueue(RESULT_DLQ, false);
        jdbc.update("DELETE FROM processed_messages");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM invoices");
        deliveries.clear();
        approvedAtAfterEachDelivery.clear();
        invoiceId = UUID.randomUUID();
        Timestamp created = Timestamp.from(Instant.now().minusSeconds(10));
        jdbc.update("""
                INSERT INTO invoices (id, access_key, status, created_at, updated_at)
                VALUES (?, ?, 'IN_ANALYSIS', ?, ?)
                """, invoiceId, KEY, created, created);
        approvedAtProbe = () -> jdbc.queryForObject("SELECT approved_at FROM invoices WHERE id = ?",
                Timestamp.class, invoiceId);
    }

    @Test
    void t7_realConsumerReceivesABrokerRedeliveryAndUpdatesTheInvoiceOnlyOnce() {
        String body = approvableResultJson(UUID.randomUUID(), UUID.randomUUID(), KEY);

        rabbitTemplate.send(EXCHANGE, RESULT_ROUTING_KEY, MessageBuilder
                .withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build());

        await().atMost(WAIT).until(() -> deliveries.size() >= 2
                && rabbitAdmin.getQueueInfo(RESULT_QUEUE).getMessageCount() == 0);
        assertThat(deliveries).as("flag redelivered de cada entrega").containsExactly(false, true);
        assertThat(approvedAtAfterEachDelivery.get(0)).as("aprovada já na 1ª entrega").isNotNull();
        assertThat(approvedAtAfterEachDelivery.get(1)).as("mesmo approvedAt depois da 2ª entrega")
                .isEqualTo(approvedAtAfterEachDelivery.get(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_messages", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM invoices WHERE id = ?", String.class, invoiceId))
                .isEqualTo("APPROVED");
        assertThat(rabbitAdmin.getQueueInfo(RESULT_DLQ).getMessageCount()).as("DLQ vazia").isZero();
    }
}
