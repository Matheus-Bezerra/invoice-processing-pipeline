package dev.matheus.invoice.adapter.in.web.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import dev.matheus.invoice.RabbitOnlyTestcontainersConfiguration;
import dev.matheus.invoice.application.service.invoice.InvoiceService;

/**
 * H7 e H8: o perfil {@code webhook} sobe só com o RabbitMQ e documenta o endpoint em português. H9 no servidor real:
 * o limite de tamanho vale também quando o corpo chega por *chunked*, sem {@code Content-Length}.
 */
@Import(RabbitOnlyTestcontainersConfiguration.class)
@ActiveProfiles("webhook")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "invoice.webhook.hmac-secret=segredo-de-teste")
class WebhookProfileTest {

    @Autowired
    private ApplicationContext context;
    @Value("${local.server.port}")
    private int port;

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void h8_startsWithoutDatasourceJpaFlywayOrTheBusinessBeans() {
        assertThat(context.getBeanNamesForType(javax.sql.DataSource.class)).isEmpty();
        assertThat(context.getBeanNamesForType(jakarta.persistence.EntityManagerFactory.class)).isEmpty();
        assertThat(context.getBeanNamesForType(org.flywaydb.core.Flyway.class)).isEmpty();
        assertThat(context.getBeanNamesForType(InvoiceService.class)).isEmpty();
        assertThat(context.containsBean("outboxRelayJob")).isFalse();
        assertThat(context.containsBean("enrichmentResultConsumer")).isFalse();
        assertThat(context.containsBean("enrichmentRequestConsumer")).isFalse();
        assertThat(context.containsBean("enrichmentWebhookController")).isTrue();
    }

    @Test
    void h8_healthIsUpWithoutADatabase() throws Exception {
        HttpResponse<String> health = get("/actuator/health");

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"").doesNotContain("\"db\"");
    }

    @Test
    void h7_swaggerUiIsAvailableAndDocumentsTheEndpointInPortuguese() throws Exception {
        HttpResponse<String> docs = get("/v3/api-docs");

        assertThat(docs.statusCode()).isEqualTo(200);
        assertThat(docs.body()).contains("/webhooks/enrichment-results")
                .contains("X-Signature")
                .contains("assinatura")
                .contains("FiscalFlow · Webhook do provedor")
                .doesNotContain("/invoices");
        assertThat(get("/swagger-ui/index.html").statusCode()).isEqualTo(200);
    }

    @Test
    void h9_chunkedBodyAboveTheLimitIs413OnTheRealServer() throws Exception {
        byte[] body = new byte[128 * 1024];
        // ofInputStream não informa Content-Length, então o cliente envia em chunked: 128 KB, o dobro do limite.
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webhooks/enrichment-results"))
                .header("Content-Type", "application/json")
                .header("X-Signature", "sha256=" + "0".repeat(64))
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("Corpo muito grande");
    }
}
