package dev.matheus.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class InvoiceApplicationTests {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private HealthEndpoint healthEndpoint;

	@Test
	void flywayCreatesTables() {
		List<String> tables = jdbcTemplate.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);

		assertThat(tables).contains("invoices", "outbox", "processed_messages", "flyway_schema_history");
	}

	@Test
	void healthIsUp() {
		assertThat(healthEndpoint.health().getStatus().getCode()).isEqualTo("UP");
	}

}
