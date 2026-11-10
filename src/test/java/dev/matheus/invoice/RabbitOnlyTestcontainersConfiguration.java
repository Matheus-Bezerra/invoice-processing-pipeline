package dev.matheus.invoice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/** Só o RabbitMQ: o perfil {@code webhook} não usa banco, então nenhum PostgreSQL sobe nos testes dele. */
@TestConfiguration(proxyBeanMethods = false)
public class RabbitOnlyTestcontainersConfiguration {

	@Bean
	@ServiceConnection
	RabbitMQContainer rabbitContainer() {
		return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.4-management"));
	}

}
