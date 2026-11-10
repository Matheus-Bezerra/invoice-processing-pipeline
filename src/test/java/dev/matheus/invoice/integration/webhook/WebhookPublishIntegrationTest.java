package dev.matheus.invoice.integration.webhook;

import static dev.matheus.invoice.config.messaging.MessagingTopology.RESULT_QUEUE;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import dev.matheus.invoice.integration.support.IntegrationTestSupport;

/**
 * H1 em nível I: o callback assinado chega ao {@code invoice-webhook} real e o resultado aparece na fila do
 * RabbitMQ real, sem alteração. Só o perfil {@code webhook} (sem consumers), para o teste ler a fila.
 */
@ActiveProfiles("webhook")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "invoice.webhook.hmac-secret=" + IntegrationTestSupport.SECRET)
class WebhookPublishIntegrationTest extends IntegrationTestSupport {

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;

    @BeforeEach
    void setUp() {
        rabbitAdmin.purgeQueue(RESULT_QUEUE, false);
    }

    @Test
    void h1_signedCallbackPublishesTheResultOnTheQueue() throws Exception {
        UUID messageId = UUID.randomUUID();
        String body = approvableResultJson(messageId, UUID.randomUUID(), KEY);

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webhooks/enrichment-results"))
                        .header("Content-Type", "application/json")
                        .header("X-Signature", sign(body, SECRET))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(202);
        Message published = rabbitTemplate.receive(RESULT_QUEUE, WAIT.toMillis());
        assertThat(published).as("resultado publicado na fila").isNotNull();
        assertThat(new String(published.getBody(), StandardCharsets.UTF_8)).isEqualTo(body);
    }
}
