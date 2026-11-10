package dev.matheus.invoice.integration.support;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * O <b>2º contexto</b> dos testes de ponta a ponta: o {@code invoice-webhook} (perfil {@code webhook}, sem banco) na
 * mesma JVM do worker, ligado ao mesmo RabbitMQ. O perfil {@code webhook} não convive com {@code api} e {@code worker}
 * no mesmo contexto, por isso são dois.
 *
 * <p>A aplicação de produção não serve de ponto de entrada aqui: sem o filtro de exclusão dos testes (que só existe
 * dentro do {@code @SpringBootTest}), o component scan encontraria as {@code @TestConfiguration} do projeto, que
 * subiriam containers por conta própria.
 */
public final class WebhookApp implements AutoCloseable {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = "dev.matheus.invoice", excludeFilters = {
            @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = TestConfiguration.class),
            @ComponentScan.Filter(type = FilterType.REGEX, pattern = "dev\\.matheus\\.invoice\\.integration\\..*")})
    static class Application {
    }

    private final ConfigurableApplicationContext context;

    public WebhookApp(String hmacSecret) {
        // Argumentos de linha de comando, e não .properties(): estas são propriedades padrão (menor precedência) e o
        // application-webhook.yml (hmac-secret: ${WEBHOOK_HMAC_SECRET:}) as sobrescreveria com o valor vazio.
        this.context = new SpringApplicationBuilder(Application.class)
                .profiles("webhook")
                .run(
                        "--server.port=0",
                        "--spring.rabbitmq.host=" + IntegrationTestSupport.RABBIT.getHost(),
                        "--spring.rabbitmq.port=" + IntegrationTestSupport.RABBIT.getAmqpPort(),
                        "--spring.rabbitmq.username=" + IntegrationTestSupport.RABBIT.getAdminUsername(),
                        "--spring.rabbitmq.password=" + IntegrationTestSupport.RABBIT.getAdminPassword(),
                        "--invoice.webhook.hmac-secret=" + hmacSecret);
    }

    public String callbackUrl() {
        return "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port")
                + "/webhooks/enrichment-results";
    }

    @Override
    public void close() {
        context.close();
    }
}
