package dev.matheus.invoice.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class InvoiceTest {

    private static final AccessKey KEY = AccessKey.of("35260112345678000199550010000012341000012345");
    private static final Instant CREATED = Instant.parse("2026-11-04T12:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:03Z");
    private static final String CNPJ = "12ABC34501DE35";
    private static final BigDecimal AMOUNT = new BigDecimal("1500.00");
    private static final LocalDate DATE = LocalDate.of(2026, 1, 10);

    private static Invoice newInvoice() {
        return Invoice.create(KEY, CREATED);
    }

    private static EnrichmentData complete() {
        return new EnrichmentData(CNPJ, AMOUNT, DATE, false);
    }

    @Test
    void newInvoiceStartsInAnalysisWithoutData() {
        Invoice invoice = newInvoice();

        assertThat(invoice.id()).isNotNull();
        assertThat(invoice.accessKey()).isEqualTo(KEY);
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.IN_ANALYSIS);
        assertThat(invoice.cnpj()).isNull();
        assertThat(invoice.amount()).isNull();
        assertThat(invoice.issueDate()).isNull();
        assertThat(invoice.cancelled()).isNull();
        assertThat(invoice.approvedAt()).isNull();
        assertThat(invoice.rejectionReason()).isNull();
        assertThat(invoice.createdAt()).isEqualTo(CREATED);
    }

    @Nested
    class T3_CompleteResultApproves {

        @Test
        void approvesAndStoresDataAndApprovedAt() {
            Invoice invoice = newInvoice();

            ProcessingOutcome outcome = invoice.applyEnrichment(complete(), NOW);

            assertThat(outcome).isEqualTo(ProcessingOutcome.APPROVED);
            assertThat(invoice.status()).isEqualTo(InvoiceStatus.APPROVED);
            assertThat(invoice.cnpj()).isEqualTo(CNPJ);
            assertThat(invoice.amount()).isEqualByComparingTo(AMOUNT);
            assertThat(invoice.issueDate()).isEqualTo(DATE);
            assertThat(invoice.cancelled()).isFalse();
            assertThat(invoice.approvedAt()).isEqualTo(NOW);
            assertThat(invoice.rejectionReason()).isNull();
            assertThat(invoice.updatedAt()).isEqualTo(NOW);
        }

        @Test
        void acceptsZeroAmount() {
            Invoice invoice = newInvoice();

            ProcessingOutcome outcome = invoice.applyEnrichment(
                    new EnrichmentData(CNPJ, BigDecimal.ZERO, DATE, false), NOW);

            assertThat(outcome).isEqualTo(ProcessingOutcome.APPROVED);
            assertThat(invoice.status()).isEqualTo(InvoiceStatus.APPROVED);
        }
    }

    @Nested
    class T4_CancelledResultRejects {

        @Test
        void rejectsWithCancellationReasonAndNoApprovedAt() {
            Invoice invoice = newInvoice();

            ProcessingOutcome outcome = invoice.applyEnrichment(
                    new EnrichmentData(CNPJ, AMOUNT, DATE, true), NOW);

            assertThat(outcome).isEqualTo(ProcessingOutcome.REJECTED);
            assertThat(invoice.status()).isEqualTo(InvoiceStatus.REJECTED);
            assertThat(invoice.rejectionReason()).isEqualTo("Nota cancelada pelo emitente");
            assertThat(invoice.approvedAt()).isNull();
            assertThat(invoice.cancelled()).isTrue();
            assertThat(invoice.cnpj()).isEqualTo(CNPJ);
            assertThat(invoice.updatedAt()).isEqualTo(NOW);
        }
    }

    @Nested
    class T5_IncompleteResultRejects {

        @Test
        void missingCnpj() {
            Invoice invoice = newInvoice();

            invoice.applyEnrichment(new EnrichmentData(null, AMOUNT, DATE, false), NOW);

            assertRejected(invoice, "Enriquecimento incompleto: campos ausentes: cnpj");
        }

        @Test
        void blankCnpjCountsAsMissing() {
            Invoice invoice = newInvoice();

            invoice.applyEnrichment(new EnrichmentData("  ", AMOUNT, DATE, false), NOW);

            assertRejected(invoice, "Enriquecimento incompleto: campos ausentes: cnpj");
        }

        @Test
        void missingAmount() {
            Invoice invoice = newInvoice();

            invoice.applyEnrichment(new EnrichmentData(CNPJ, null, DATE, false), NOW);

            assertRejected(invoice, "Enriquecimento incompleto: campos ausentes: amount");
        }

        @Test
        void missingIssueDate() {
            Invoice invoice = newInvoice();

            invoice.applyEnrichment(new EnrichmentData(CNPJ, AMOUNT, null, false), NOW);

            assertRejected(invoice, "Enriquecimento incompleto: campos ausentes: issueDate");
        }

        @Test
        void listsMissingFieldsInFixedOrder() {
            Invoice invoice = newInvoice();

            invoice.applyEnrichment(new EnrichmentData(null, null, null, null), NOW);

            assertRejected(invoice,
                    "Enriquecimento incompleto: campos ausentes: cnpj, amount, issueDate, cancelled");
        }
    }

    @Nested
    class E1_MissingCancelledRejectsAsIncomplete {

        @Test
        void nullCancelledIsNeverTreatedAsFalse() {
            Invoice invoice = newInvoice();

            ProcessingOutcome outcome = invoice.applyEnrichment(
                    new EnrichmentData(CNPJ, AMOUNT, DATE, null), NOW);

            assertThat(outcome).isEqualTo(ProcessingOutcome.REJECTED);
            assertRejected(invoice, "Enriquecimento incompleto: campos ausentes: cancelled");
        }
    }

    @Nested
    class E2_InvalidDataRejects {

        @Test
        void invalidCnpj() {
            Invoice invoice = newInvoice();

            invoice.applyEnrichment(new EnrichmentData("12ABC34501DE36", AMOUNT, DATE, false), NOW);

            assertRejected(invoice, "Dados de enriquecimento inválidos: cnpj inválido");
            assertThat(invoice.cnpj()).isEqualTo("12ABC34501DE36");
        }

        @Test
        void negativeAmount() {
            Invoice invoice = newInvoice();

            invoice.applyEnrichment(new EnrichmentData(CNPJ, new BigDecimal("-0.01"), DATE, false), NOW);

            assertRejected(invoice, "Dados de enriquecimento inválidos: amount negativo");
        }

        @Test
        void invalidCnpjAndNegativeAmount() {
            Invoice invoice = newInvoice();

            invoice.applyEnrichment(new EnrichmentData("123", new BigDecimal("-1"), DATE, false), NOW);

            assertRejected(invoice, "Dados de enriquecimento inválidos: cnpj inválido, amount negativo");
        }

        @Test
        void amountAboveTheLimitIsRejectedInsteadOfApproved() {
            Invoice invoice = newInvoice();

            invoice.applyEnrichment(new EnrichmentData(CNPJ, new BigDecimal("10000000000000.00"), DATE, false), NOW);

            assertRejected(invoice, "Dados de enriquecimento inválidos: amount acima do limite");
            assertThat(invoice.amount()).isNull();
        }

        @Test
        void largestAcceptedAmountIsStillApproved() {
            Invoice invoice = newInvoice();

            invoice.applyEnrichment(new EnrichmentData(CNPJ, new BigDecimal("9999999999999.99"), DATE, false), NOW);

            assertThat(invoice.status()).isEqualTo(InvoiceStatus.APPROVED);
        }

        @Test
        void amountThatRoundsAboveTheLimitIsRejected() {
            Invoice invoice = newInvoice();

            invoice.applyEnrichment(new EnrichmentData(CNPJ, new BigDecimal("9999999999999.995"), DATE, false), NOW);

            assertRejected(invoice, "Dados de enriquecimento inválidos: amount acima do limite");
        }

        @Test
        void absurdExponentIsRejectedWithoutExpandingTheNumber() {
            Invoice invoice = newInvoice();

            invoice.applyEnrichment(new EnrichmentData(CNPJ, new BigDecimal("1E+1000000000"), DATE, false), NOW);

            assertRejected(invoice, "Dados de enriquecimento inválidos: amount acima do limite");
        }

        @Test
        void cnpjLongerThanTheColumnIsRejectedAndNotStored() {
            Invoice invoice = newInvoice();

            invoice.applyEnrichment(new EnrichmentData("12ABC34501DE35XXXXXX", AMOUNT, DATE, false), NOW);

            assertRejected(invoice, "Dados de enriquecimento inválidos: cnpj inválido");
            assertThat(invoice.cnpj()).isNull();
            assertThat(invoice.amount()).isEqualByComparingTo(AMOUNT);
        }

        @Test
        void negativeAmountTooBigToStoreIsReportedAsNegativeAndNotStored() {
            Invoice invoice = newInvoice();

            invoice.applyEnrichment(new EnrichmentData(CNPJ, new BigDecimal("-99999999999999999"), DATE, false), NOW);

            assertRejected(invoice, "Dados de enriquecimento inválidos: amount negativo");
            assertThat(invoice.amount()).isNull();
        }

        @Test
        void cancelledNoteWithDataThatDoesNotFitIsStillRejectedAsCancelled() {
            Invoice invoice = newInvoice();

            invoice.applyEnrichment(new EnrichmentData("X".repeat(40), new BigDecimal("1E+30"), DATE, true), NOW);

            assertRejected(invoice, "Nota cancelada pelo emitente");
            assertThat(invoice.cnpj()).isNull();
            assertThat(invoice.amount()).isNull();
            assertThat(invoice.cancelled()).isTrue();
        }
    }

    @Nested
    class E10_CancellationHasPriority {

        @Test
        void cancelledAndIncompleteGivesCancellationReason() {
            Invoice invoice = newInvoice();

            invoice.applyEnrichment(new EnrichmentData(null, null, DATE, true), NOW);

            assertRejected(invoice, "Nota cancelada pelo emitente");
        }

        @Test
        void cancelledAndInvalidGivesCancellationReason() {
            Invoice invoice = newInvoice();

            invoice.applyEnrichment(new EnrichmentData("123", new BigDecimal("-1"), DATE, true), NOW);

            assertRejected(invoice, "Nota cancelada pelo emitente");
        }

        @Test
        void incompleteHasPriorityOverInvalid() {
            Invoice invoice = newInvoice();

            invoice.applyEnrichment(new EnrichmentData("123", null, DATE, false), NOW);

            assertRejected(invoice, "Enriquecimento incompleto: campos ausentes: amount");
        }
    }

    @Nested
    class FinalizedInvoiceDoesNotChange {

        private static final Instant LATER = Instant.parse("2026-11-04T13:00:00Z");

        @Test
        void approvedInvoiceIgnoresNewResult() {
            Invoice invoice = newInvoice();
            invoice.applyEnrichment(complete(), NOW);

            ProcessingOutcome outcome = invoice.applyEnrichment(
                    new EnrichmentData("11222333000181", new BigDecimal("1.00"), DATE.plusDays(1), true), LATER);

            assertThat(outcome).isEqualTo(ProcessingOutcome.IGNORED_ALREADY_FINAL);
            assertThat(invoice.status()).isEqualTo(InvoiceStatus.APPROVED);
            assertThat(invoice.cnpj()).isEqualTo(CNPJ);
            assertThat(invoice.amount()).isEqualByComparingTo(AMOUNT);
            assertThat(invoice.issueDate()).isEqualTo(DATE);
            assertThat(invoice.cancelled()).isFalse();
            assertThat(invoice.approvedAt()).isEqualTo(NOW);
            assertThat(invoice.rejectionReason()).isNull();
            assertThat(invoice.updatedAt()).isEqualTo(NOW);
        }

        @Test
        void rejectedInvoiceIgnoresNewResult() {
            Invoice invoice = newInvoice();
            invoice.applyEnrichment(new EnrichmentData(CNPJ, AMOUNT, DATE, true), NOW);

            ProcessingOutcome outcome = invoice.applyEnrichment(complete(), LATER);

            assertThat(outcome).isEqualTo(ProcessingOutcome.IGNORED_ALREADY_FINAL);
            assertThat(invoice.status()).isEqualTo(InvoiceStatus.REJECTED);
            assertThat(invoice.rejectionReason()).isEqualTo("Nota cancelada pelo emitente");
            assertThat(invoice.cancelled()).isTrue();
            assertThat(invoice.approvedAt()).isNull();
            assertThat(invoice.updatedAt()).isEqualTo(NOW);
        }
    }

    private static void assertRejected(Invoice invoice, String reason) {
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(invoice.rejectionReason()).isEqualTo(reason);
        assertThat(invoice.approvedAt()).isNull();
        assertThat(invoice.updatedAt()).isEqualTo(NOW);
    }
}
