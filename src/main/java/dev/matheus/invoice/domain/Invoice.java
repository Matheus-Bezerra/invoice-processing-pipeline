package dev.matheus.invoice.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public class Invoice {

    static final String CANCELLED_REASON = "Nota cancelada pelo emitente";
    static final String INCOMPLETE_REASON = "Enriquecimento incompleto: campos ausentes: ";
    static final String INVALID_REASON = "Dados de enriquecimento inválidos: ";

    private final UUID id;
    private final AccessKey accessKey;
    private final Instant createdAt;
    private InvoiceStatus status;
    private String cnpj;
    private BigDecimal amount;
    private LocalDate issueDate;
    private Boolean cancelled;
    private Instant approvedAt;
    private String rejectionReason;
    private Instant updatedAt;

    private Invoice(UUID id, AccessKey accessKey, Instant createdAt) {
        this.id = id;
        this.accessKey = accessKey;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.status = InvoiceStatus.IN_ANALYSIS;
    }

    public static Invoice create(AccessKey accessKey, Instant now) {
        return new Invoice(UUID.randomUUID(), accessKey, now);
    }

    /** Reconstitui uma nota já persistida, sem aplicar regras. Uso exclusivo dos adapters de persistência. */
    public static Invoice restore(UUID id, AccessKey accessKey, InvoiceStatus status, String cnpj, BigDecimal amount,
            LocalDate issueDate, Boolean cancelled, Instant approvedAt, String rejectionReason, Instant createdAt,
            Instant updatedAt) {
        Invoice invoice = new Invoice(id, accessKey, createdAt);
        invoice.status = status;
        invoice.cnpj = cnpj;
        invoice.amount = amount;
        invoice.issueDate = issueDate;
        invoice.cancelled = cancelled;
        invoice.approvedAt = approvedAt;
        invoice.rejectionReason = rejectionReason;
        invoice.updatedAt = updatedAt;
        return invoice;
    }

    /**
     * Aplica o resultado do provedor. Ordem das regras: cancelada, incompleta, inválida, aprova.
     * Nota já finalizada não é alterada.
     */
    public ProcessingOutcome applyEnrichment(EnrichmentData data, Instant now) {
        if (status != InvoiceStatus.IN_ANALYSIS) {
            return ProcessingOutcome.IGNORED_ALREADY_FINAL;
        }
        if (Boolean.TRUE.equals(data.cancelled())) {
            return reject(CANCELLED_REASON, data, now);
        }
        List<String> missing = missingFields(data);
        if (!missing.isEmpty()) {
            return reject(INCOMPLETE_REASON + String.join(", ", missing), data, now);
        }
        List<String> invalid = invalidFields(data);
        if (!invalid.isEmpty()) {
            return reject(INVALID_REASON + String.join(", ", invalid), data, now);
        }
        return approve(data, now);
    }

    private static List<String> missingFields(EnrichmentData data) {
        List<String> missing = new ArrayList<>();
        if (data.cnpj() == null || data.cnpj().isBlank()) {
            missing.add("cnpj");
        }
        if (data.amount() == null) {
            missing.add("amount");
        }
        if (data.issueDate() == null) {
            missing.add("issueDate");
        }
        if (data.cancelled() == null) {
            missing.add("cancelled");
        }
        return missing;
    }

    private static List<String> invalidFields(EnrichmentData data) {
        List<String> invalid = new ArrayList<>();
        if (!Cnpj.isValid(data.cnpj())) {
            invalid.add("cnpj inválido");
        }
        if (data.amount().signum() < 0) {
            invalid.add("amount negativo");
        }
        return invalid;
    }

    private ProcessingOutcome approve(EnrichmentData data, Instant now) {
        storeData(data);
        this.status = InvoiceStatus.APPROVED;
        this.approvedAt = now;
        this.updatedAt = now;
        return ProcessingOutcome.APPROVED;
    }

    private ProcessingOutcome reject(String reason, EnrichmentData data, Instant now) {
        storeData(data);
        this.status = InvoiceStatus.REJECTED;
        this.rejectionReason = reason;
        this.updatedAt = now;
        return ProcessingOutcome.REJECTED;
    }

    /** Grava o que veio, inclusive em notas rejeitadas. */
    private void storeData(EnrichmentData data) {
        this.cnpj = data.cnpj();
        this.amount = data.amount();
        this.issueDate = data.issueDate();
        this.cancelled = data.cancelled();
    }
}
