package dev.matheus.invoice.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import dev.matheus.invoice.TestcontainersConfiguration;
import dev.matheus.invoice.application.service.EnrichmentResultCommand;
import dev.matheus.invoice.application.service.EnrichmentResultService;
import dev.matheus.invoice.application.service.ResultHandling;
import dev.matheus.invoice.application.service.UnknownAccessKeyException;
import dev.matheus.invoice.domain.AccessKey;
import dev.matheus.invoice.domain.EnrichmentData;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class EnrichmentResultPersistenceTest {

    private static final String KEY = "35260112345678000199550010000012341000012345";
    private static final EnrichmentData APPROVABLE =
            new EnrichmentData("12ABC34501DE35", new BigDecimal("1500.00"), LocalDate.of(2026, 1, 10), false);

    @Autowired
    private EnrichmentResultService service;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID invoiceId;

    @BeforeEach
    void insertInvoiceInAnalysis() {
        jdbc.update("DELETE FROM processed_messages");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM invoices");
        invoiceId = UUID.randomUUID();
        Timestamp created = Timestamp.from(Instant.now().minusSeconds(10));
        jdbc.update("""
                INSERT INTO invoices (id, access_key, status, created_at, updated_at)
                VALUES (?, ?, 'IN_ANALYSIS', ?, ?)
                """, invoiceId, KEY, created, created);
    }

    private ResultHandling process(UUID messageId, EnrichmentData data) {
        return service.process(new EnrichmentResultCommand(messageId, invoiceId, AccessKey.of(KEY), data));
    }

    private Map<String, Object> invoiceRow() {
        return jdbc.queryForMap("SELECT * FROM invoices WHERE id = ?", invoiceId);
    }

    private int processedCount(UUID messageId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_messages WHERE message_id = ?", Integer.class,
                messageId);
    }

    @Test
    void approvalStoresDataApprovedAtAndTheMessageId() {
        UUID messageId = UUID.randomUUID();

        assertThat(process(messageId, APPROVABLE)).isEqualTo(ResultHandling.APPROVED);

        Map<String, Object> row = invoiceRow();
        assertThat(row.get("status")).isEqualTo("APPROVED");
        assertThat(row.get("cnpj")).isEqualTo("12ABC34501DE35");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("1500.00");
        assertThat(row.get("issue_date").toString()).isEqualTo("2026-01-10");
        assertThat(row.get("cancelled")).isEqualTo(false);
        assertThat(row.get("approved_at")).isNotNull();
        assertThat(row.get("rejection_reason")).isNull();
        assertThat(processedCount(messageId)).isEqualTo(1);
    }

    @Test
    void rejectionStoresReasonAndReceivedDataWithoutApprovedAt() {
        EnrichmentData cancelled = new EnrichmentData("12ABC34501DE35", new BigDecimal("10.00"), null, true);

        assertThat(process(UUID.randomUUID(), cancelled)).isEqualTo(ResultHandling.REJECTED);

        Map<String, Object> row = invoiceRow();
        assertThat(row.get("status")).isEqualTo("REJECTED");
        assertThat(row.get("rejection_reason")).isEqualTo("Nota cancelada pelo emitente");
        assertThat(row.get("cancelled")).isEqualTo(true);
        assertThat(row.get("approved_at")).isNull();
    }

    @Test
    void t7_sameMessageTwiceUpdatesOnlyOnce() {
        UUID messageId = UUID.randomUUID();
        process(messageId, APPROVABLE);
        Object approvedAt = invoiceRow().get("approved_at");
        Object updatedAt = invoiceRow().get("updated_at");

        assertThat(process(messageId, APPROVABLE)).isEqualTo(ResultHandling.DUPLICATE);

        assertThat(invoiceRow().get("approved_at")).isEqualTo(approvedAt);
        assertThat(invoiceRow().get("updated_at")).isEqualTo(updatedAt);
        assertThat(processedCount(messageId)).isEqualTo(1);
    }

    @Test
    void e3_differentMessageForAFinalizedInvoiceChangesNothing() {
        process(UUID.randomUUID(), APPROVABLE);
        Map<String, Object> before = invoiceRow();

        EnrichmentData cancelled = new EnrichmentData("12ABC34501DE35", new BigDecimal("1.00"), null, true);
        assertThat(process(UUID.randomUUID(), cancelled)).isEqualTo(ResultHandling.IGNORED_ALREADY_FINAL);

        assertThat(invoiceRow()).isEqualTo(before);
    }

    @Test
    void t8_unknownAccessKeyCreatesNothingAndDoesNotRecordTheMessage() {
        UUID messageId = UUID.randomUUID();
        AccessKey unknown = AccessKey.of("9".repeat(44));

        assertThatThrownBy(() -> service.process(
                new EnrichmentResultCommand(messageId, UUID.randomUUID(), unknown, APPROVABLE)))
                .isInstanceOf(UnknownAccessKeyException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices", Integer.class)).isEqualTo(1);
        assertThat(processedCount(messageId)).isZero();
    }

    @Test
    void concurrentDeliveriesForTheSameInvoiceApplyOnlyOneResult() throws Exception {
        int deliveries = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(deliveries);
        List<Future<ResultHandling>> results = new ArrayList<>();
        try {
            for (int i = 0; i < deliveries; i++) {
                UUID messageId = UUID.randomUUID();
                Callable<ResultHandling> task = () -> {
                    start.await();
                    return process(messageId, APPROVABLE);
                };
                results.add(pool.submit(task));
            }
            start.countDown();

            List<ResultHandling> handled = new ArrayList<>();
            for (Future<ResultHandling> result : results) {
                handled.add(result.get());
            }
            assertThat(handled).containsOnlyOnce(ResultHandling.APPROVED);
            assertThat(handled).filteredOn(h -> h == ResultHandling.IGNORED_ALREADY_FINAL).hasSize(deliveries - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(invoiceRow().get("status")).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_messages", Integer.class))
                .isEqualTo(deliveries);
    }
}
