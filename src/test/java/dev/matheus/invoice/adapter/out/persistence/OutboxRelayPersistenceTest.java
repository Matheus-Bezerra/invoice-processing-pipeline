package dev.matheus.invoice.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import dev.matheus.invoice.TestcontainersConfiguration;
import dev.matheus.invoice.application.port.out.messaging.EnrichmentRequestPublisher;
import dev.matheus.invoice.application.service.invoice.InvoiceService;
import dev.matheus.invoice.application.service.outbox.OutboxPublisherService;
import dev.matheus.invoice.domain.AccessKey;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OutboxRelayPersistenceTest {

    @Autowired
    private OutboxPublisherService outboxPublisher;
    @Autowired
    private InvoiceService invoiceService;
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @Qualifier("immediatePublishExecutor")
    private ExecutorService immediatePublishExecutor;

    @MockitoBean
    private EnrichmentRequestPublisher publisher;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM invoices");
    }

    private int outboxCount() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class);
    }

    /** Grava uma nota e uma solicitação pendente já vencida, sem passar pelo service (sem publicação imediata). */
    private UUID insertPendingRequest(int index) {
        UUID invoiceId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        String accessKey = String.format("%044d", index + 1);
        Timestamp past = Timestamp.from(Instant.now().minusSeconds(1));
        jdbc.update("""
                INSERT INTO invoices (id, access_key, status, created_at, updated_at)
                VALUES (?, ?, 'IN_ANALYSIS', ?, ?)
                """, invoiceId, accessKey, past, past);
        jdbc.update("""
                INSERT INTO outbox (id, invoice_id, access_key, payload, attempts, next_attempt_at, created_at)
                VALUES (?, ?, ?, CAST(? AS jsonb), 0, ?, ?)
                """, messageId, invoiceId, accessKey, "{\"messageId\":\"" + messageId + "\"}", past, past);
        return messageId;
    }

    @Test
    void e8_rowIsDeletedAfterTheConfirmedPublication() {
        UUID messageId = insertPendingRequest(0);

        assertThat(outboxPublisher.publishDue()).isEqualTo(1);

        verify(publisher).publish(any(), any(), any());
        assertThat(outboxCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE id = ?", Integer.class, messageId))
                .isZero();
    }

    @Test
    void e8_immediatePublicationAfterTheCommitDeletesTheRow() {
        UUID invoiceId = invoiceService.create(AccessKey.of("35260112345678000199550010000012341000012345"))
                .invoice().id();

        // A publicação imediata roda em outra thread, depois do commit.
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher).publish(any(), org.mockito.ArgumentMatchers.eq(invoiceId), any());
            assertThat(outboxCount()).isZero();
        });
        assertThat(jdbc.queryForObject("SELECT status FROM invoices WHERE id = ?", String.class, invoiceId))
                .isEqualTo("IN_ANALYSIS");
    }

    @Test
    void createDoesNotWaitForTheBrokerWhenThePublicationIsSlow() throws Exception {
        CountDownLatch releasePublisher = new CountDownLatch(1);
        doAnswer(invocation -> {
            releasePublisher.await(10, TimeUnit.SECONDS);
            return null;
        }).when(publisher).publish(any(), any(), any());

        try {
            // Com o publisher travado, a criação precisa terminar mesmo assim (o POST não espera o broker).
            assertThat(java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> invoiceService.create(
                            AccessKey.of("35260112345678000199550010000012341000012345")))
                    .get(3, TimeUnit.SECONDS).created()).isTrue();
            assertThat(outboxCount()).isEqualTo(1);
        } finally {
            releasePublisher.countDown();
        }

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(outboxCount()).isZero());
    }

    @Test
    void saturatedExecutorLeavesTheExcessInTheOutboxAndTheRelayPublishesIt() throws Exception {
        ThreadPoolExecutor pool = (ThreadPoolExecutor) immediatePublishExecutor;
        long completedBefore = pool.getCompletedTaskCount();
        int created = 120; // mais do que o executor aceita (2 threads + fila de 100 + 2 threads extras)

        Queue<UUID> published = new ConcurrentLinkedQueue<>();
        CountDownLatch releasePublisher = new CountDownLatch(1);
        doAnswer(invocation -> {
            published.add(invocation.getArgument(0));
            releasePublisher.await(30, TimeUnit.SECONDS);
            return null;
        }).when(publisher).publish(any(), any(), any());

        try {
            // Publisher travado: o executor enche e começa a rejeitar, mas nenhuma criação falha nem demora.
            for (int i = 0; i < created; i++) {
                String accessKey = String.format("%044d", 1_000 + i);
                assertThat(invoiceService.create(AccessKey.of(accessKey)).created()).isTrue();
            }
            assertThat(outboxCount()).isEqualTo(created);
        } finally {
            releasePublisher.countDown();
        }

        // O executor termina o que aceitou e apaga essas linhas.
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            long accepted = pool.getCompletedTaskCount() - completedBefore;
            assertThat(pool.getActiveCount()).isZero();
            assertThat(pool.getQueue()).isEmpty();
            assertThat(accepted).isLessThan(created);
            assertThat(outboxCount()).isEqualTo(created - (int) accepted);
        });
        int excess = outboxCount();
        assertThat(excess).as("o executor saturou e rejeitou parte das tarefas").isPositive();

        // As rejeitadas ficaram no outbox, prontas para o relay, que as publica sem repetir nenhuma.
        assertThat(outboxPublisher.publishDue()).isEqualTo(excess);

        assertThat(outboxCount()).isZero();
        assertThat(published).hasSize(created).doesNotHaveDuplicates();
    }

    @Test
    void t9_failedPublicationKeepsTheRowWithBackoffAndTheInvoiceUntouched() {
        doThrow(new IllegalStateException("broker fora do ar")).when(publisher).publish(any(), any(), any());
        UUID invoiceId = invoiceService.create(AccessKey.of("35260112345678000199550010000012341000012345"))
                .invoice().id();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT attempts FROM outbox", Integer.class)).isEqualTo(1));
        Map<String, Object> row = jdbc.queryForMap("SELECT attempts, next_attempt_at, last_error FROM outbox");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(((Timestamp) row.get("next_attempt_at")).toInstant()).isAfter(Instant.now());
        assertThat((String) row.get("last_error")).contains("broker fora do ar");
        assertThat(jdbc.queryForObject("SELECT status FROM invoices WHERE id = ?", String.class, invoiceId))
                .isEqualTo("IN_ANALYSIS");

        // Antes do horário do backoff, o relay não pega a linha de novo.
        assertThat(outboxPublisher.publishDue()).isZero();
        verify(publisher, times(1)).publish(any(), any(), any());

        // Depois do horário, com o broker de volta, o relay publica e apaga a linha.
        jdbc.update("UPDATE outbox SET next_attempt_at = now() - interval '1 second'");
        org.mockito.Mockito.reset(publisher);
        assertThat(outboxPublisher.publishDue()).isEqualTo(1);
        assertThat(outboxCount()).isZero();
    }

    @Test
    void e9_twoSimultaneousRelaysNeverPublishTheSameRowTwice() throws Exception {
        int rows = 20;
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            expected.add(insertPendingRequest(i));
        }

        Queue<UUID> published = new ConcurrentLinkedQueue<>();
        CountDownLatch firstPublishing = new CountDownLatch(1);
        CountDownLatch secondRelayDone = new CountDownLatch(1);
        doAnswer(invocation -> {
            published.add(invocation.getArgument(0));
            firstPublishing.countDown();
            // Segura o primeiro relay com as linhas travadas até o segundo terminar a tentativa dele.
            secondRelayDone.await(10, TimeUnit.SECONDS);
            return null;
        }).when(publisher).publish(any(), any(), any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(outboxPublisher::publishDue);
            assertThat(firstPublishing.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> second = executor.submit(() -> {
                try {
                    return outboxPublisher.publishDue();
                } finally {
                    secondRelayDone.countDown();
                }
            });

            assertThat(second.get(10, TimeUnit.SECONDS)).isZero();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(rows);
        } finally {
            executor.shutdownNow();
        }

        assertThat(published).hasSize(rows).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        assertThat(outboxCount()).isZero();
    }
}
