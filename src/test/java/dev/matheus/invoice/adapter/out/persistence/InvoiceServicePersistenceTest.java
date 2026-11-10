package dev.matheus.invoice.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import dev.matheus.invoice.TestcontainersConfiguration;
import dev.matheus.invoice.application.port.out.messaging.EnrichmentRequestPublisher;
import dev.matheus.invoice.application.port.out.messaging.RequestPublishFailedException;
import dev.matheus.invoice.application.service.invoice.CreateInvoiceResult;
import dev.matheus.invoice.application.service.invoice.InvoiceService;
import dev.matheus.invoice.domain.AccessKey;
import dev.matheus.invoice.domain.Invoice;
import dev.matheus.invoice.domain.InvoiceStatus;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class InvoiceServicePersistenceTest {

    private static final AccessKey KEY = AccessKey.of("35260112345678000199550010000012341000012345");

    @Autowired
    private InvoiceService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** Broker indisponível: a linha do outbox continua lá depois do commit, e estes testes podem contá-la. */
    @MockitoBean
    private EnrichmentRequestPublisher publisher;

    @BeforeEach
    void cleanTables() {
        doThrow(new RequestPublishFailedException("broker indisponível no teste", null)).when(publisher).publish(any(), any(), any());
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM invoices");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test
    void createPersistsTheInvoiceAndTheOutboxRowAndReadsThemBack() {
        CreateInvoiceResult result = service.create(KEY);

        assertThat(result.created()).isTrue();
        Invoice stored = service.getById(result.invoice().id());
        assertThat(stored.accessKey()).isEqualTo(KEY);
        assertThat(stored.status()).isEqualTo(InvoiceStatus.IN_ANALYSIS);
        assertThat(stored.approvedAt()).isNull();
        assertThat(stored.cnpj()).isNull();
        assertThat(stored.cancelled()).isNull();
        assertThat(stored.createdAt()).isEqualTo(result.invoice().createdAt());
        assertThat(service.getByAccessKey(KEY).id()).isEqualTo(stored.id());

        assertThat(count("outbox")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT invoice_id FROM outbox", UUID.class)).isEqualTo(stored.id());
        assertThat(jdbc.queryForObject("SELECT payload->>'correlationId' FROM outbox", String.class))
                .isEqualTo(stored.id().toString());
        assertThat(jdbc.queryForObject("SELECT payload->>'messageId' = id::text FROM outbox", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT payload->>'eventType' FROM outbox", String.class))
                .isEqualTo("invoice.enrichment.requested");
        assertThat(jdbc.queryForObject("SELECT payload->>'occurredAt' FROM outbox", String.class)).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT payload->'payload'->>'invoiceId' FROM outbox", String.class))
                .isEqualTo(stored.id().toString());
        assertThat(jdbc.queryForObject("SELECT payload->'payload'->>'accessKey' FROM outbox", String.class))
                .isEqualTo(KEY.value());
    }

    @Test
    void e7_repeatedKeyReturnsTheSameInvoiceAndDoesNotCreateAnotherOutboxRow() {
        CreateInvoiceResult first = service.create(KEY);
        CreateInvoiceResult second = service.create(KEY);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.invoice().id()).isEqualTo(first.invoice().id());
        assertThat(count("invoices")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void t6_simultaneousRequestsWithTheSameKeyKeepASingleInvoice() throws Exception {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CreateInvoiceResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return service.create(KEY);
                }));
            }
            start.countDown();
            List<CreateInvoiceResult> results = new ArrayList<>();
            for (Future<CreateInvoiceResult> future : futures) {
                results.add(future.get());
            }

            assertThat(results).filteredOn(CreateInvoiceResult::created).hasSize(1);
            assertThat(results).extracting(r -> r.invoice().id()).containsOnly(results.get(0).invoice().id());
            assertThat(count("invoices")).isEqualTo(1);
            assertThat(count("outbox")).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invoiceAndOutboxRowShareTheSameTransaction() {
        transactionTemplate.executeWithoutResult(status -> {
            service.create(KEY);
            status.setRollbackOnly();
        });

        assertThat(count("invoices")).isZero();
        assertThat(count("outbox")).isZero();
    }
}
