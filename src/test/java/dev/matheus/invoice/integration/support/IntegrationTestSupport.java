package dev.matheus.invoice.integration.support;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base dos testes de integração da fatia 7: RabbitMQ e PostgreSQL reais, <b>um par de containers por JVM</b>
 * compartilhado por todas as classes (os contextos Spring mudam, o broker e o banco não). A tag {@code integration}
 * (herdada) permite rodar só estes testes com {@code ./mvnw test -Pintegration}.
 *
 * <p>O broker compartilhado nunca é parado nem pausado. Quem precisa derrubar o broker (T9) usa um container próprio.
 */
@Tag("integration")
public abstract class IntegrationTestSupport {

    public static final String KEY = "35260112345678000199550010000012341000012345";
    public static final String SECRET = "segredo-de-teste-da-integracao";
    public static final Duration WAIT = Duration.ofSeconds(20);

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
    public static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.4-management"));

    static {
        Startables.deepStart(POSTGRES, RABBIT).join();
    }

    @DynamicPropertySource
    static void sharedContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    /** {@code sha256=<hex>} do HMAC-SHA256 dos bytes do corpo, como o webhook confere. */
    public static String sign(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Mesma regra do simulador (e da spec): o messageId do resultado deriva do messageId da solicitação. */
    public static UUID resultMessageIdFor(UUID requestMessageId) {
        return UUID.nameUUIDFromBytes(("result:" + requestMessageId).getBytes(StandardCharsets.UTF_8));
    }

    /** Resultado completo e aprovável (spec, seção 3.2), com o CNPJ alfanumérico válido do exemplo da spec. */
    public static String approvableResultJson(UUID messageId, UUID correlationId, String accessKey) {
        return """
                {"messageId":"%s","eventType":"invoice.enrichment.completed","occurredAt":"2026-11-04T12:00:02Z",
                 "correlationId":"%s","payload":{"accessKey":"%s","cnpj":"12ABC34501DE35","amount":1500.00,
                 "issueDate":"2026-01-10","cancelled":false}}
                """.formatted(messageId, correlationId, accessKey);
    }
}
