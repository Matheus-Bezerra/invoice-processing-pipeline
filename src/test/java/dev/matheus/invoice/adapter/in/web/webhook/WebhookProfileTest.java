package dev.matheus.invoice.adapter.in.web.webhook;

import static org.assertj.core.api.Assertions.assertThat;

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

/** H7 e H8: o perfil {@code webhook} sobe só com o RabbitMQ e documenta o endpoint em português. */
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
                .doesNotContain("/invoices");
        assertThat(get("/swagger-ui/index.html").statusCode()).isEqualTo(200);
    }
}
