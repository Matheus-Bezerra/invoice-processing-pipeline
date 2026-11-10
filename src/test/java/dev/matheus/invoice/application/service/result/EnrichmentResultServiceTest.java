package dev.matheus.invoice.application.service.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import dev.matheus.invoice.application.port.out.clock.ClockPort;
import dev.matheus.invoice.application.port.out.persistence.invoice.InvoiceRepository;
import dev.matheus.invoice.application.port.out.persistence.processedmessage.ProcessedMessageStore;
import dev.matheus.invoice.domain.AccessKey;
import dev.matheus.invoice.domain.EnrichmentData;
import dev.matheus.invoice.domain.Invoice;
import dev.matheus.invoice.domain.InvoiceStatus;

@ExtendWith(MockitoExtension.class)
class EnrichmentResultServiceTest {

    private static final AccessKey KEY = AccessKey.of("35260112345678000199550010000012341000012345");
    private static final Instant CREATED = Instant.parse("2026-11-04T12:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:03Z");
    private static final String CNPJ = "12ABC34501DE35";
    private static final BigDecimal AMOUNT = new BigDecimal("1500.00");
    private static final LocalDate DATE = LocalDate.of(2026, 1, 10);

    @Mock
    private InvoiceRepository invoices;
    @Mock
    private ProcessedMessageStore processedMessages;
    @Mock
    private ClockPort clock;

    private EnrichmentResultService service;
    private Invoice invoice;
    private final UUID messageId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new EnrichmentResultService(invoices, processedMessages, clock);
        invoice = Invoice.create(KEY, CREATED);
    }

    private EnrichmentResultCommand command(EnrichmentData data) {
        return new EnrichmentResultCommand(messageId, invoice.id(), KEY, data);
    }

    private void invoiceExistsAndMessageIsNew() {
        when(clock.now()).thenReturn(NOW);
        when(invoices.findByAccessKeyForUpdate(KEY)).thenReturn(Optional.of(invoice));
        when(processedMessages.markProcessed(messageId, NOW)).thenReturn(true);
    }

    private Invoice updatedInvoice() {
        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        verify(invoices).updateEnrichment(captor.capture());
        return captor.getValue();
    }

    @Test
    void t3_completeAndNotCancelledResultApprovesWithDataAndApprovedAt() {
        invoiceExistsAndMessageIsNew();
        when(invoices.updateEnrichment(any())).thenReturn(true);

        ResultHandling handling = service.process(command(new EnrichmentData(CNPJ, AMOUNT, DATE, false)));

        assertThat(handling).isEqualTo(ResultHandling.APPROVED);
        Invoice updated = updatedInvoice();
        assertThat(updated.status()).isEqualTo(InvoiceStatus.APPROVED);
        assertThat(updated.cnpj()).isEqualTo(CNPJ);
        assertThat(updated.amount()).isEqualByComparingTo(AMOUNT);
        assertThat(updated.issueDate()).isEqualTo(DATE);
        assertThat(updated.cancelled()).isFalse();
        assertThat(updated.approvedAt()).isEqualTo(NOW);
        assertThat(updated.rejectionReason()).isNull();
    }

    @Test
    void t4_cancelledResultRejectsWithReasonAndWithoutApprovedAt() {
        invoiceExistsAndMessageIsNew();
        when(invoices.updateEnrichment(any())).thenReturn(true);

        ResultHandling handling = service.process(command(new EnrichmentData(CNPJ, AMOUNT, DATE, true)));

        assertThat(handling).isEqualTo(ResultHandling.REJECTED);
        Invoice updated = updatedInvoice();
        assertThat(updated.status()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(updated.rejectionReason()).isEqualTo("Nota cancelada pelo emitente");
        assertThat(updated.approvedAt()).isNull();
    }

    @Test
    void t5_missingCnpjRejectsAsIncomplete() {
        assertRejectedAsIncomplete(new EnrichmentData(null, AMOUNT, DATE, false), "cnpj");
    }

    @Test
    void t5_missingAmountRejectsAsIncomplete() {
        assertRejectedAsIncomplete(new EnrichmentData(CNPJ, null, DATE, false), "amount");
    }

    @Test
    void t5_missingIssueDateRejectsAsIncomplete() {
        assertRejectedAsIncomplete(new EnrichmentData(CNPJ, AMOUNT, null, false), "issueDate");
    }

    private void assertRejectedAsIncomplete(EnrichmentData data, String field) {
        invoiceExistsAndMessageIsNew();
        when(invoices.updateEnrichment(any())).thenReturn(true);

        ResultHandling handling = service.process(command(data));

        assertThat(handling).isEqualTo(ResultHandling.REJECTED);
        Invoice updated = updatedInvoice();
        assertThat(updated.status()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(updated.rejectionReason()).isEqualTo("Enriquecimento incompleto: campos ausentes: " + field);
        assertThat(updated.approvedAt()).isNull();
    }

    @Test
    void t7_alreadyProcessedMessageDoesNotUpdateAgain() {
        when(clock.now()).thenReturn(NOW);
        when(invoices.findByAccessKeyForUpdate(KEY)).thenReturn(Optional.of(invoice));
        when(processedMessages.markProcessed(messageId, NOW)).thenReturn(false);

        ResultHandling handling = service.process(command(new EnrichmentData(CNPJ, AMOUNT, DATE, false)));

        assertThat(handling).isEqualTo(ResultHandling.DUPLICATE);
        verify(invoices, never()).updateEnrichment(any());
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.IN_ANALYSIS);
    }

    @Test
    void t8_unknownAccessKeyFailsWithoutRecordingTheMessage() {
        when(invoices.findByAccessKeyForUpdate(KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.process(command(new EnrichmentData(CNPJ, AMOUNT, DATE, false))))
                .isInstanceOf(UnknownAccessKeyException.class);

        verifyNoInteractions(processedMessages);
        verify(invoices, never()).updateEnrichment(any());
    }

    @Test
    void e3_differentMessageForAFinalizedInvoiceChangesNothing() {
        Instant approvedAt = Instant.parse("2026-11-04T12:00:01Z");
        invoice = Invoice.restore(UUID.randomUUID(), KEY, InvoiceStatus.APPROVED, CNPJ, AMOUNT, DATE, false,
                approvedAt, null, CREATED, approvedAt);
        invoiceExistsAndMessageIsNew();

        ResultHandling handling = service.process(command(new EnrichmentData(CNPJ, AMOUNT, DATE, true)));

        assertThat(handling).isEqualTo(ResultHandling.IGNORED_ALREADY_FINAL);
        verify(invoices, never()).updateEnrichment(any());
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.APPROVED);
        assertThat(invoice.approvedAt()).isEqualTo(approvedAt);
        assertThat(invoice.rejectionReason()).isNull();
    }

    @Test
    void e5_technicalErrorPropagatesSoTheConsumerRetries() {
        when(invoices.findByAccessKeyForUpdate(KEY))
                .thenThrow(new DataAccessResourceFailureException("banco fora do ar"));

        assertThatThrownBy(() -> service.process(command(new EnrichmentData(CNPJ, AMOUNT, DATE, false))))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verifyNoInteractions(processedMessages);
    }

    @Test
    void stateGuardLostRaceIsReportedAsAlreadyFinal() {
        invoiceExistsAndMessageIsNew();
        when(invoices.updateEnrichment(any())).thenReturn(false);

        ResultHandling handling = service.process(command(new EnrichmentData(CNPJ, AMOUNT, DATE, false)));

        assertThat(handling).isEqualTo(ResultHandling.IGNORED_ALREADY_FINAL);
    }
}
