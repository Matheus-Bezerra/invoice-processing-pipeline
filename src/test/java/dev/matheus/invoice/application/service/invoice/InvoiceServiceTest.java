package dev.matheus.invoice.application.service.invoice;

import dev.matheus.invoice.application.service.outbox.OutboxPublisherService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import dev.matheus.invoice.application.port.out.clock.ClockPort;
import dev.matheus.invoice.application.port.out.persistence.invoice.InvoiceRepository;
import dev.matheus.invoice.application.port.out.persistence.outbox.OutboxPort;
import dev.matheus.invoice.domain.AccessKey;
import dev.matheus.invoice.domain.Invoice;
import dev.matheus.invoice.domain.InvoiceStatus;
import dev.matheus.invoice.domain.exception.InvoiceNotFoundException;
import tools.jackson.databind.JsonNode;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    private static final AccessKey KEY = AccessKey.of("35260112345678000199550010000012341000012345");
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

    @Mock
    private InvoiceRepository invoices;
    @Mock
    private OutboxPort outbox;
    @Mock
    private ClockPort clock;
    @Mock
    private OutboxPublisherService outboxPublisher;
    @Mock
    private ExecutorService executor;

    private InvoiceService service;

    @BeforeEach
    void setUp() {
        service = new InvoiceService(invoices, outbox, clock, outboxPublisher, executor);
    }

    @Test
    void t2_newInvoiceStaysInAnalysisWithoutApprovalWhileNoResultExists() {
        when(clock.now()).thenReturn(NOW);
        when(invoices.insertIfAbsent(any())).thenReturn(true);

        CreateInvoiceResult result = service.create(KEY);

        assertThat(result.created()).isTrue();
        assertThat(result.invoice().status()).isEqualTo(InvoiceStatus.IN_ANALYSIS);
        assertThat(result.invoice().approvedAt()).isNull();
        assertThat(result.invoice().createdAt()).isEqualTo(NOW);
        ArgumentCaptor<Invoice> saved = ArgumentCaptor.forClass(Invoice.class);
        verify(invoices).insertIfAbsent(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(InvoiceStatus.IN_ANALYSIS);
    }

    @Test
    void newInvoiceEnqueuesTheRequestWithTheInvoiceAndTheKey() {
        when(clock.now()).thenReturn(NOW);
        when(invoices.insertIfAbsent(any())).thenReturn(true);

        Invoice invoice = service.create(KEY).invoice();

        verify(outbox).enqueue(any(UUID.class), org.mockito.ArgumentMatchers.eq(invoice.id()),
                org.mockito.ArgumentMatchers.eq(KEY), org.mockito.ArgumentMatchers.eq(NOW));
    }

    @Test
    void repeatedKeyReturnsTheExistingInvoiceAndDoesNotTouchTheOutbox() {
        when(clock.now()).thenReturn(NOW);
        Invoice existing = Invoice.create(KEY, Instant.parse("2026-11-03T10:00:00Z"));
        when(invoices.insertIfAbsent(any())).thenReturn(false);
        when(invoices.findByAccessKey(KEY)).thenReturn(Optional.of(existing));

        CreateInvoiceResult result = service.create(KEY);

        assertThat(result.created()).isFalse();
        assertThat(result.invoice()).isSameAs(existing);
        verifyNoInteractions(outbox);
    }

    @Nested
    class ImmediatePublication {

        @BeforeEach
        void startSynchronization() {
            TransactionSynchronizationManager.initSynchronization();
        }

        @AfterEach
        void clearSynchronization() {
            TransactionSynchronizationManager.clearSynchronization();
        }

        private UUID createAndCaptureMessageId() {
            when(clock.now()).thenReturn(NOW);
            when(invoices.insertIfAbsent(any())).thenReturn(true);
            service.create(KEY);
            ArgumentCaptor<UUID> messageId = ArgumentCaptor.forClass(UUID.class);
            verify(outbox).enqueue(messageId.capture(), any(), any(), any());
            return messageId.getValue();
        }

        private void commit() {
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        }

        private Runnable submittedTask() {
            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            verify(executor).execute(task.capture());
            return task.getValue();
        }

        @Test
        void newInvoiceHandsThePublicationToTheExecutorOnlyAfterTheCommit() {
            UUID messageId = createAndCaptureMessageId();

            verify(executor, never()).execute(any());
            commit();

            verify(outboxPublisher, never()).publish(any()); // ainda não rodou: o request não espera o broker
            submittedTask().run();
            verify(outboxPublisher).publish(messageId);
        }

        @Test
        void publicationFailureInTheBackgroundDoesNotPropagate() {
            UUID messageId = createAndCaptureMessageId();
            doThrow(new IllegalStateException("broker fora do ar")).when(outboxPublisher).publish(messageId);
            commit();

            submittedTask().run();

            verify(outboxPublisher).publish(messageId);
        }

        @Test
        void fullExecutorQueueDoesNotFailTheRequest() {
            createAndCaptureMessageId();
            doThrow(new RejectedExecutionException("fila cheia")).when(executor).execute(any());

            commit();

            verifyNoInteractions(outboxPublisher);
        }

        @Test
        void repeatedKeyRegistersNoPublication() {
            when(clock.now()).thenReturn(NOW);
            when(invoices.insertIfAbsent(any())).thenReturn(false);
            when(invoices.findByAccessKey(KEY)).thenReturn(Optional.of(Invoice.create(KEY, NOW)));

            service.create(KEY);

            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        }
    }

    @Test
    void getByIdReturnsTheInvoice() {
        Invoice existing = Invoice.create(KEY, NOW);
        when(invoices.findById(existing.id())).thenReturn(Optional.of(existing));

        assertThat(service.getById(existing.id())).isSameAs(existing);
    }

    @Test
    void getByIdFailsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(invoices.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(InvoiceNotFoundException.class);
    }

    @Test
    void getByAccessKeyFailsWhenMissing() {
        when(invoices.findByAccessKey(KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByAccessKey(KEY)).isInstanceOf(InvoiceNotFoundException.class);
    }
}
