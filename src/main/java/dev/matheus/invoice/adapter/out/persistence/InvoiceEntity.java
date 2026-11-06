package dev.matheus.invoice.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import dev.matheus.invoice.domain.InvoiceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Entity
@Table(name = "invoices")
public class InvoiceEntity {

    @Id
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "access_key", nullable = false, length = 44, columnDefinition = "char(44)")
    private String accessKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvoiceStatus status;

    @Column(length = 14)
    private String cnpj;

    @Column(precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    private Boolean cancelled;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
