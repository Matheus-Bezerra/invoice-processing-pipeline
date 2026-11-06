package dev.matheus.invoice.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class InvoiceRestoreTest {

    @Test
    void restoreRebuildsTheStoredStateWithoutApplyingRules() {
        UUID id = UUID.randomUUID();
        AccessKey key = AccessKey.of("35260112345678000199550010000012341000012345");
        Instant created = Instant.parse("2026-11-04T12:00:00Z");
        Instant approved = Instant.parse("2026-11-04T12:00:03Z");

        Invoice invoice = Invoice.restore(id, key, InvoiceStatus.APPROVED, "12ABC34501DE35",
                new BigDecimal("1500.00"), LocalDate.of(2026, 1, 10), false, approved, null, created, approved);

        assertThat(invoice.id()).isEqualTo(id);
        assertThat(invoice.accessKey()).isEqualTo(key);
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.APPROVED);
        assertThat(invoice.cnpj()).isEqualTo("12ABC34501DE35");
        assertThat(invoice.amount()).isEqualByComparingTo("1500.00");
        assertThat(invoice.issueDate()).isEqualTo(LocalDate.of(2026, 1, 10));
        assertThat(invoice.cancelled()).isFalse();
        assertThat(invoice.approvedAt()).isEqualTo(approved);
        assertThat(invoice.rejectionReason()).isNull();
        assertThat(invoice.createdAt()).isEqualTo(created);
        assertThat(invoice.updatedAt()).isEqualTo(approved);
    }
}
