package dev.matheus.invoice.integration.outbox;

import static dev.matheus.invoice.integration.support.IntegrationTestSupport.KEY;
import static dev.matheus.invoice.integration.support.IntegrationTestSupport.POSTGRES;
import static dev.matheus.invoice.integration.support.IntegrationTestSupport.WAIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import dev.matheus.invoice.integration.support.FakeProvider;
import dev.matheus.invoice.integration.support.IntegrationTestSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T9 em nível I: falha de publicação seguida de recuperação, com o broker de verdade fora do ar.
 *
 * <p>Usa um <b>RabbitMQ só deste teste</b> (o PostgreSQL é o compartilhado): pausar o broker compartilhado
 * travaria as outras classes. O container é pausado com {@code docker pause}, que preserva as portas mapeadas, ao
 * contrário de parar e religar. Com o broker pausado a publicação imediata não recebe o <i>confirm</i>, a nota e a
 * linha do outbox ficam gravadas e a tentativa é registrada; ao despausar, o relay do worker publica a solicitação
 * pendente com o <b>mesmo {@code messageId}</b> (o {@code id} da linha do outbox) e apaga a linha.
 */
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles({"api", "worker"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "invoice.outbox.relay.tick=1s",
        "invoice.messaging.confirm-timeout=1s",
        "spring.rabbitmq.connection-timeout=1s",
        // Com o broker pausado a conexão já aberta continua de pé; abrir um canal novo espera a resposta do broker,
        // e o padrão desse espera é de 10 minutos.
        "spring.rabbitmq.channel-rpc-timeout=1s"
})
class OutboxRecoveryIntegrationTest {

    static final RabbitMQContainer OWN_RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.4-management"));
    static final FakeProvider PROVIDER = new FakeProvider(IntegrationTestSupport.SECRET);

    static {
        OWN_RABBIT.start();
        PROVIDER.mode(FakeProvider.Mode.SILENT);
    }

    @DynamicPropertySource
    static void ownBroker(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", OWN_RABBIT::getHost);
        registry.add("spring.rabbitmq.port", OWN_RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", OWN_RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", OWN_RABBIT::getAdminPassword);
        registry.add("invoice.provider.base-url", PROVIDER::baseUrl);
    }

    @AfterAll
    static void stop() {
        PROVIDER.close();
        // O broker próprio NÃO é parado aqui: o @AfterAll roda antes de o Spring fechar o contexto (@DirtiesContext),
        // e um broker que some com o contexto ainda de pé gera erros de conexão em threads soltas que o Awaitility
        // atribui, por engano, ao teste seguinte. O Testcontainers remove o container ao fim da JVM.
    }

    private final JsonMapper json = JsonMapper.builder().build();

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM processed_messages");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM invoices");
        PROVIDER.reset();
        PROVIDER.mode(FakeProvider.Mode.SILENT);
    }

    @Test
    void t9_publishFailureKeepsTheInvoiceAndTheRelayPublishesAfterTheBrokerComesBack() throws Exception {
        OWN_RABBIT.getDockerClient().pauseContainerCmd(OWN_RABBIT.getContainerId()).exec();
        boolean paused = true;
        UUID messageId;
        try {
            UUID invoiceId = createInvoice();

            // Falha registrada: a linha do outbox continua lá, com tentativa contada e o erro gravado.
            await().atMost(WAIT).until(() -> !jdbc.queryForList(
                    "SELECT 1 FROM outbox WHERE invoice_id = ? AND attempts >= 1 AND last_error IS NOT NULL",
                    invoiceId).isEmpty());
            Map<String, Object> row = jdbc.queryForMap("SELECT id FROM outbox WHERE invoice_id = ?", invoiceId);
            messageId = (UUID) row.get("id");
            assertThat(jdbc.queryForObject("SELECT status FROM invoices WHERE id = ?", String.class, invoiceId))
                    .isEqualTo("IN_ANALYSIS");
            assertThat(PROVIDER.calls()).as("nada chegou ao provedor com o broker fora").isEmpty();

            OWN_RABBIT.getDockerClient().unpauseContainerCmd(OWN_RABBIT.getContainerId()).exec();
            paused = false;
        } finally {
            if (paused) {
                OWN_RABBIT.getDockerClient().unpauseContainerCmd(OWN_RABBIT.getContainerId()).exec();
            }
        }

        // O relay publica, a linha é apagada e o worker repassa a solicitação ao provedor.
        await().atMost(java.time.Duration.ofSeconds(60)).until(() ->
                jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class) == 0
                        && !PROVIDER.calls().isEmpty());
        // At-least-once: se o broker processou a publicação feita durante a pausa, pode haver cópia. Todas
        // carregam o mesmo messageId (o id da linha do outbox), que é o que o consumer usa para deduplicar.
        List<FakeProvider.Call> calls = PROVIDER.calls();
        for (FakeProvider.Call call : calls) {
            JsonNode envelope = json.readTree(call.body());
            assertThat(envelope.get("messageId").asString()).isEqualTo(messageId.toString());
        }
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
}
