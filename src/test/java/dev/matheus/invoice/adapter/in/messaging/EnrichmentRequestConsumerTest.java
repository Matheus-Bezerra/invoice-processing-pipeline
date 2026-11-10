package dev.matheus.invoice.adapter.in.messaging;

import static dev.matheus.invoice.config.messaging.MessagingTopology.EXCHANGE;
import static dev.matheus.invoice.config.messaging.MessagingTopology.REQUEST_DLQ;
import static dev.matheus.invoice.config.messaging.MessagingTopology.REQUEST_QUEUE;
import static dev.matheus.invoice.config.messaging.MessagingTopology.REQUEST_ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpServer;

import dev.matheus.invoice.TestcontainersConfiguration;

/**
 * Consumer da solicitação real com o RabbitMQ real e um provedor fake (servidor HTTP do JDK): C1 a C4 em nível I.
 * Esperas do retry encurtadas para 100 ms e timeout de resposta de 300 ms.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("worker")
@SpringBootTest(properties = {
        "invoice.messaging.request-retry.initial-interval=100ms",
        "invoice.messaging.request-retry.max-interval=400ms",
        "invoice.provider.read-timeout=300ms"
})
class EnrichmentRequestConsumerTest {

    private static final String KEY = "35260112345678000199550010000012341000012345";
    private static final Duration WAIT = Duration.ofSeconds(15);

    private static HttpServer provider;
    private static volatile int status;
    private static volatile long delayMillis;
    private static final AtomicInteger calls = new AtomicInteger();
    private static final List<String> correlationHeaders = new CopyOnWriteArrayList<>();
    private static final List<String> bodies = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startProvider() throws IOException {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/enrichment-requests", exchange -> {
            calls.incrementAndGet();
            correlationHeaders.add(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMillis);
                exchange.sendResponseHeaders(status, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // cliente desistiu (timeout)
            } finally {
                exchange.close();
            }
        });
        // Várias threads: com a padrão (uma só), chamadas lentas se enfileiram e a contagem vaza para o teste seguinte.
        provider.setExecutor(Executors.newCachedThreadPool());
        provider.start();
    }

    @AfterAll
    static void stopProvider() {
        provider.stop(0);
    }

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) throws IOException {
        // O servidor precisa existir antes do contexto: a porta é conhecida só depois de criá-lo.
        if (provider == null) {
            startProvider();
        }
        registry.add("invoice.provider.base-url", () -> "http://127.0.0.1:" + provider.getAddress().getPort());
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;

    @BeforeEach
    void setUp() {
        rabbitAdmin.purgeQueue(REQUEST_QUEUE, false);
        rabbitAdmin.purgeQueue(REQUEST_DLQ, false);
        calls.set(0);
        correlationHeaders.clear();
        bodies.clear();
        status = 202;
        delayMillis = 0;
    }

    private static String requestJson(UUID messageId, UUID invoiceId) {
        return """
                {"messageId":"%s","eventType":"invoice.enrichment.requested","occurredAt":"2026-11-04T12:00:00Z",
                 "correlationId":"%s","payload":{"invoiceId":"%s","accessKey":"%s"}}
                """.formatted(messageId, invoiceId, invoiceId, KEY);
    }

    private void publish(String body) {
        Message message = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
        rabbitTemplate.send(EXCHANGE, REQUEST_ROUTING_KEY, message);
    }

    private Message awaitDeadLetter() {
        await().atMost(WAIT).until(() -> rabbitAdmin.getQueueInfo(REQUEST_DLQ).getMessageCount() == 1);
        return rabbitTemplate.receive(REQUEST_DLQ, 2_000);
    }

    @Test
    void c1_acceptedRequestIsForwardedOnceAndAcked() {
        UUID invoiceId = UUID.randomUUID();
        String body = requestJson(UUID.randomUUID(), invoiceId);

        publish(body);

        await().atMost(WAIT).until(() -> calls.get() == 1
                && rabbitAdmin.getQueueInfo(REQUEST_QUEUE).getMessageCount() == 0);
        assertThat(bodies).containsExactly(body);
        assertThat(correlationHeaders).containsExactly(invoiceId.toString());
        assertThat(rabbitAdmin.getQueueInfo(REQUEST_DLQ).getMessageCount()).isZero();
    }

    @Test
    void c2_providerClientErrorGoesStraightToTheDeadLetterQueue() {
        status = 400;
        String body = requestJson(UUID.randomUUID(), UUID.randomUUID());

        publish(body);

        Message dead = awaitDeadLetter();
        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo(body);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void c3_providerServerErrorRetriesThreeTimesAndThenGoesToTheDeadLetterQueue() {
        status = 503;

        publish(requestJson(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(awaitDeadLetter()).isNotNull();
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    void c3_providerWithoutResponseRetriesThreeTimesAndThenGoesToTheDeadLetterQueue() {
        delayMillis = 800;

        publish(requestJson(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(awaitDeadLetter()).isNotNull();
        await().atMost(WAIT).until(() -> calls.get() == 4);
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    void c4_malformedRequestGoesToTheDeadLetterQueueWithoutCallingTheProvider() {
        publish("isto nao e json");

        assertThat(awaitDeadLetter()).isNotNull();
        assertThat(calls.get()).isZero();
    }
}
