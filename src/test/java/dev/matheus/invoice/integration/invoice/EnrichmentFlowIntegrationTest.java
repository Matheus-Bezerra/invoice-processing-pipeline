package dev.matheus.invoice.integration.invoice;

import static dev.matheus.invoice.config.messaging.MessagingTopology.EXCHANGE;
import static dev.matheus.invoice.config.messaging.MessagingTopology.REQUEST_DLQ;
import static dev.matheus.invoice.config.messaging.MessagingTopology.REQUEST_QUEUE;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_DLQ;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_QUEUE;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import dev.matheus.invoice.application.service.result.EnrichmentResultService;
import dev.matheus.invoice.integration.support.FakeProvider;
import dev.matheus.invoice.integration.support.IntegrationTestSupport;
import dev.matheus.invoice.integration.support.WebhookApp;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * O caminho completo com RabbitMQ e PostgreSQL reais: {@code POST /invoices} → outbox → fila → worker → provedor
 * HTTP de teste → callback assinado → {@code invoice-webhook} (2º contexto) → fila de resultado → consumer → nota.
 * Cobre C1, C3, T3, H6 e T8 em nível I. Esperas do retry da solicitação encurtadas para 100 ms.
 *
 * <p>O contexto é descartado no fim da classe: o broker é compartilhado, e um worker esquecido em cache disputaria
 * as filas com as outras classes.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles({"api", "worker"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "invoice.messaging.request-retry.initial-interval=100ms",
        "invoice.messaging.request-retry.max-interval=400ms",
        // Só a publicação imediata: o relay (5s) poderia reenviar a mesma solicitação e falsear a contagem de chamadas.
        "invoice.outbox.relay.tick=1h"
})
class EnrichmentFlowIntegrationTest extends IntegrationTestSupport {

    static final FakeProvider PROVIDER = new FakeProvider(SECRET);
    static WebhookApp webhook;

    private final JsonMapper json = JsonMapper.builder().build();

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        registry.add("invoice.provider.base-url", PROVIDER::baseUrl);
    }

    @BeforeAll
    static void startWebhook() {
        webhook = new WebhookApp(SECRET);
        PROVIDER.webhookUrl(webhook.callbackUrl());
    }

    @AfterAll
    static void stopWebhookAndProvider() {
        webhook.close();
        PROVIDER.close();
    }

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoSpyBean
    private EnrichmentResultService resultService;

    @BeforeEach
    void setUp() {
        for (String queue : new String[] {REQUEST_QUEUE, REQUEST_DLQ, RESULT_QUEUE, RESULT_DLQ}) {
            rabbitAdmin.purgeQueue(queue, false);
        }
        jdbc.update("DELETE FROM processed_messages");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM invoices");
        PROVIDER.reset();
    }

    @Test
    void c1_requestIsForwardedToTheProviderWithTheRightBodyAndHeaderAndAcked() throws Exception {
        PROVIDER.mode(FakeProvider.Mode.SILENT);

        UUID invoiceId = createInvoice();

        await().atMost(WAIT).until(() -> PROVIDER.calls().size() == 1
                && rabbitAdmin.getQueueInfo(REQUEST_QUEUE).getMessageCount() == 0);
        FakeProvider.Call call = PROVIDER.calls().get(0);
        assertThat(call.correlationId()).isEqualTo(invoiceId.toString());
        assertThat(call.contentType()).startsWith("application/json");
        JsonNode envelope = json.readTree(call.body());
        assertThat(envelope.get("eventType").asString()).isEqualTo("invoice.enrichment.requested");
        assertThat(envelope.get("correlationId").asString()).isEqualTo(invoiceId.toString());
        assertThat(envelope.get("payload").get("invoiceId").asString()).isEqualTo(invoiceId.toString());
        assertThat(envelope.get("payload").get("accessKey").asString()).isEqualTo(KEY);
        assertThat(rabbitAdmin.getQueueInfo(REQUEST_DLQ).getMessageCount()).isZero();
        assertThat(invoiceStatus(invoiceId)).isEqualTo("IN_ANALYSIS");
    }

    @Test
    void c3_providerServerErrorRetriesThreeTimesAndThenGoesToTheRequestDeadLetterQueue() throws Exception {
        PROVIDER.mode(FakeProvider.Mode.SERVER_ERROR);

        UUID invoiceId = createInvoice();

        await().atMost(WAIT).until(() -> rabbitAdmin.getQueueInfo(REQUEST_DLQ).getMessageCount() == 1);
        assertThat(PROVIDER.calls()).as("1 chamada + 3 retries").hasSize(4);
        assertThat(rabbitAdmin.getQueueInfo(REQUEST_QUEUE).getMessageCount()).isZero();
        assertThat(invoiceStatus(invoiceId)).isEqualTo("IN_ANALYSIS");
    }

    @Test
    void t3_signedCallbackThroughTheWebhookApprovesTheInvoice() throws Exception {
        PROVIDER.mode(FakeProvider.Mode.ACCEPT);

        UUID invoiceId = createInvoice();

        await().atMost(WAIT).until(() -> "APPROVED".equals(invoiceStatus(invoiceId)));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT cnpj, amount, issue_date, cancelled, approved_at FROM invoices WHERE id = ?", invoiceId);
        assertThat(row.get("cnpj")).isEqualTo("12ABC34501DE35");
        assertThat(row.get("amount")).hasToString("1500.00");
        assertThat(row.get("issue_date")).hasToString("2026-01-10");
        assertThat(row.get("cancelled")).isEqualTo(false);
        assertThat(row.get("approved_at")).isNotNull();
        assertThat(PROVIDER.callbackStatuses()).containsExactly(202);
        assertThat(processedCount()).isEqualTo(1);
        assertThat(rabbitAdmin.getQueueInfo(RESULT_DLQ).getMessageCount()).isZero();
    }

    @Test
    void h6_sameCallbackSentTwiceUpdatesTheInvoiceOnlyOnce() throws Exception {
        PROVIDER.mode(FakeProvider.Mode.SILENT);
        UUID invoiceId = createInvoice();
        await().atMost(WAIT).until(() -> PROVIDER.calls().size() == 1);
        JsonNode request = json.readTree(PROVIDER.calls().get(0).body());
        UUID requestMessageId = UUID.fromString(request.get("messageId").asString());
        String body = approvableResultJson(resultMessageIdFor(requestMessageId), invoiceId, KEY);

        assertThat(callWebhook(body)).isEqualTo(202);
        assertThat(callWebhook(body)).isEqualTo(202);

        await().atMost(WAIT).untilAsserted(() -> verify(resultService, times(2)).process(any()));
        await().atMost(WAIT).until(() -> "APPROVED".equals(invoiceStatus(invoiceId)));
        Timestamp approvedAt = approvedAt(invoiceId);
        assertThat(approvedAt).isNotNull();
        assertThat(processedCount()).as("uma só linha em processed_messages").isEqualTo(1);
        assertThat(approvedAt(invoiceId)).as("approvedAt não muda com a duplicata").isEqualTo(approvedAt);
        assertThat(rabbitAdmin.getQueueInfo(RESULT_DLQ).getMessageCount()).isZero();
    }

    @Test
    void t8_resultForAnUnknownAccessKeyGoesToTheResultDeadLetterQueueAndCreatesNothing() {
        String body = approvableResultJson(UUID.randomUUID(), UUID.randomUUID(), "9".repeat(44));

        rabbitTemplate.send(EXCHANGE, RESULT_ROUTING_KEY, MessageBuilder
                .withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build());

        Message dead = rabbitTemplate.receive(RESULT_DLQ, WAIT.toMillis());
        assertThat(dead).as("mensagem na DLQ do resultado").isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo(body);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices", Integer.class)).isZero();
        assertThat(processedCount()).isZero();
    }

    private UUID createInvoice() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/invoices"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"accessKey\":\"" + KEY + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(json.readTree(response.body()).get("id").asString());
    }

    private int callWebhook(String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(webhook.callbackUrl()))
                .header("Content-Type", "application/json")
                .header("X-Signature", sign(body, SECRET))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private String invoiceStatus(UUID id) {
        List<String> status = jdbc.queryForList("SELECT status FROM invoices WHERE id = ?", String.class, id);
        return status.isEmpty() ? null : status.get(0);
    }

    private Timestamp approvedAt(UUID id) {
        return jdbc.queryForObject("SELECT approved_at FROM invoices WHERE id = ?", Timestamp.class, id);
    }

    private int processedCount() {
        return jdbc.queryForObject("SELECT count(*) FROM processed_messages", Integer.class);
    }
}
