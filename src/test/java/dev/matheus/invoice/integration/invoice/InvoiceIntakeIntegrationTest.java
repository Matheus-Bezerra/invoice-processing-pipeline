package dev.matheus.invoice.integration.invoice;

import static dev.matheus.invoice.config.messaging.MessagingTopology.REQUEST_DLQ;
import static dev.matheus.invoice.config.messaging.MessagingTopology.REQUEST_QUEUE;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_DLQ;
import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_QUEUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import dev.matheus.invoice.integration.support.IntegrationTestSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T1 em nível I: o {@code POST /invoices} grava a nota e a solicitação chega à fila do RabbitMQ real. Só o perfil
 * {@code api} (sem worker), para nenhum consumer tirar a mensagem da fila antes do teste.
 */
@ActiveProfiles("api")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InvoiceIntakeIntegrationTest extends IntegrationTestSupport {

    private final JsonMapper json = JsonMapper.builder().build();

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        for (String queue : new String[] {REQUEST_QUEUE, REQUEST_DLQ, RESULT_QUEUE, RESULT_DLQ}) {
            rabbitAdmin.purgeQueue(queue, false);
        }
        jdbc.update("DELETE FROM processed_messages");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM invoices");
    }

    @Test
    void t1_postStoresTheInvoiceAndTheRequestReachesTheQueue() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/invoices"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"accessKey\":\"" + KEY + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode invoice = json.readTree(response.body());
        assertThat(invoice.get("status").asString()).isEqualTo("IN_ANALYSIS");
        assertThat(invoice.get("approvedAt").isNull()).isTrue();
        String invoiceId = invoice.get("id").asString();

        Message message = rabbitTemplate.receive(REQUEST_QUEUE, WAIT.toMillis());
        assertThat(message).as("solicitação publicada na fila").isNotNull();
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        JsonNode envelope = json.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
        assertThat(envelope.get("eventType").asString()).isEqualTo("invoice.enrichment.requested");
        assertThat(envelope.get("messageId").asString()).isNotBlank();
        assertThat(envelope.get("correlationId").asString()).isEqualTo(invoiceId);
        assertThat(envelope.get("payload").get("invoiceId").asString()).isEqualTo(invoiceId);
        assertThat(envelope.get("payload").get("accessKey").asString()).isEqualTo(KEY);

        // E8 em nível I: a linha do outbox é apagada depois da publicação confirmada.
        await().atMost(WAIT).until(() -> jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class) == 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices", Integer.class)).isEqualTo(1);
    }
}
