package dev.matheus.invoice.adapter.in.messaging;

import static dev.matheus.invoice.config.messaging.MessagingTopology.EXCHANGE;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_DLQ;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_QUEUE;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import dev.matheus.invoice.TestcontainersConfiguration;
import dev.matheus.invoice.application.service.result.EnrichmentResultService;

/** Consumer real com o RabbitMQ real: roteamento para a DLQ e retry. Esperas do retry encurtadas para 100 ms. */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("worker")
@SpringBootTest(properties = {
        "invoice.messaging.result-retry.initial-interval=100ms",
        "invoice.messaging.result-retry.max-interval=400ms"
})
class EnrichmentResultConsumerTest {

    private static final String KEY = "35260112345678000199550010000012341000012345";
    private static final Duration DLQ_WAIT = Duration.ofSeconds(10);

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private EnrichmentResultService service;

    private UUID invoiceId;

    @BeforeEach
    void setUp() {
        rabbitAdmin.purgeQueue(RESULT_QUEUE, false);
        rabbitAdmin.purgeQueue(RESULT_DLQ, false);
        jdbc.update("DELETE FROM processed_messages");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM invoices");
        invoiceId = UUID.randomUUID();
        Timestamp created = Timestamp.from(Instant.now().minusSeconds(10));
        jdbc.update("""
                INSERT INTO invoices (id, access_key, status, created_at, updated_at)
                VALUES (?, ?, 'IN_ANALYSIS', ?, ?)
                """, invoiceId, KEY, created, created);
    }

    private static String result(String messageId, String accessKey, String amount, String issueDate) {
        return """
                {
                  "messageId": %s,
                  "eventType": "invoice.enrichment.completed",
                  "occurredAt": "2026-11-04T12:00:02Z",
                  "correlationId": "0b6f5c1e-8d2a-4c53-9a43-6f4b6a0f2f11",
                  "payload": {
                    "accessKey": %s,
                    "cnpj": "12ABC34501DE35",
                    "amount": %s,
                    "issueDate": %s,
                    "cancelled": false
                  }
                }
                """.formatted(messageId, accessKey, amount, issueDate);
    }

    private static String quoted(String value) {
        return "\"" + value + "\"";
    }

    private static String validResult(UUID messageId, String accessKey) {
        return result(quoted(messageId.toString()), quoted(accessKey), "1500.00", quoted("2026-01-10"));
    }

    private void publish(String body) {
        Message message = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
        rabbitTemplate.send(EXCHANGE, RESULT_ROUTING_KEY, message);
    }

    private Message receiveFromDlq() {
        return rabbitTemplate.receive(RESULT_DLQ, DLQ_WAIT.toMillis());
    }

    private String invoiceStatus() {
        return jdbc.queryForObject("SELECT status FROM invoices WHERE id = ?", String.class, invoiceId);
    }

    private int processedCount() {
        return jdbc.queryForObject("SELECT count(*) FROM processed_messages", Integer.class);
    }

    @Test
    void approvableResultApprovesTheInvoice() {
        publish(validResult(UUID.randomUUID(), KEY));

        await().atMost(DLQ_WAIT).until(() -> "APPROVED".equals(invoiceStatus()));
        assertThat(processedCount()).isEqualTo(1);
        assertThat(rabbitTemplate.receive(RESULT_DLQ, 500)).isNull();
    }

    static Stream<Arguments> malformedMessages() {
        String messageId = quoted(UUID.randomUUID().toString());
        return Stream.of(
                Arguments.of("JSON inválido", "isto não é json"),
                Arguments.of("sem messageId", result("null", quoted(KEY), "1500.00", quoted("2026-01-10"))),
                Arguments.of("messageId que não é UUID", result(quoted("abc"), quoted(KEY), "1500.00", quoted("2026-01-10"))),
                Arguments.of("sem accessKey", result(messageId, "null", "1500.00", quoted("2026-01-10"))),
                Arguments.of("accessKey com 43 dígitos", result(messageId, quoted(KEY.substring(1)), "1500.00", quoted("2026-01-10"))),
                Arguments.of("amount ilegível", result(messageId, quoted(KEY), quoted("abc"), quoted("2026-01-10"))),
                Arguments.of("data impossível", result(messageId, quoted(KEY), "1500.00", quoted("2026-02-30"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedMessages")
    void e4_malformedMessageGoesStraightToTheDlq(String description, String body) {
        publish(body);

        Message deadLettered = receiveFromDlq();
        assertThat(deadLettered).isNotNull();
        assertThat(new String(deadLettered.getBody(), StandardCharsets.UTF_8)).isEqualTo(body);
        verify(service, never()).process(any());
        assertThat(invoiceStatus()).isEqualTo("IN_ANALYSIS");
        assertThat(processedCount()).isZero();
    }

    @Test
    void t8_unknownAccessKeyGoesToTheDlqWithoutRetryAndCreatesNothing() {
        String body = validResult(UUID.randomUUID(), "9".repeat(44));

        publish(body);

        Message deadLettered = receiveFromDlq();
        assertThat(deadLettered).isNotNull();
        assertThat(new String(deadLettered.getBody(), StandardCharsets.UTF_8)).isEqualTo(body);
        verify(service, times(1)).process(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices", Integer.class)).isEqualTo(1);
        assertThat(invoiceStatus()).isEqualTo("IN_ANALYSIS");
        assertThat(processedCount()).isZero();
    }

    @Test
    void e5_technicalErrorIsRetriedThreeTimesAndThenGoesToTheDlq() {
        doThrow(new DataAccessResourceFailureException("banco fora do ar")).when(service).process(any());
        String body = validResult(UUID.randomUUID(), KEY);

        publish(body);

        Message deadLettered = receiveFromDlq();
        assertThat(deadLettered).isNotNull();
        assertThat(new String(deadLettered.getBody(), StandardCharsets.UTF_8)).isEqualTo(body);
        verify(service, times(4)).process(any());
        assertThat(invoiceStatus()).isEqualTo("IN_ANALYSIS");
    }
}
