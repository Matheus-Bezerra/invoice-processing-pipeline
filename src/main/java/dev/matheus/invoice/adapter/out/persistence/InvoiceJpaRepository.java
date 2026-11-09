package dev.matheus.invoice.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.matheus.invoice.domain.InvoiceStatus;

interface InvoiceJpaRepository extends JpaRepository<InvoiceEntity, UUID> {

    Optional<InvoiceEntity> findByAccessKey(String accessKey);

    /** Devolve 1 se inseriu e 0 se a chave já existia (a unicidade vem da constraint do banco). */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO invoices (id, access_key, status, created_at, updated_at)
            VALUES (:id, :accessKey, :status, :createdAt, :updatedAt)
            ON CONFLICT (access_key) DO NOTHING
            """)
    int insertIfAbsent(@Param("id") UUID id, @Param("accessKey") String accessKey, @Param("status") String status,
            @Param("createdAt") Instant createdAt, @Param("updatedAt") Instant updatedAt);

    @Query(nativeQuery = true, value = "SELECT * FROM invoices WHERE access_key = :accessKey FOR UPDATE")
    Optional<InvoiceEntity> findByAccessKeyForUpdate(@Param("accessKey") String accessKey);

    /**
     * Devolve 1 se atualizou e 0 se a nota já estava finalizada (guarda de estado). Em JPQL, e não nativa, para os
     * parâmetros nulos (dados ausentes numa rejeição) terem o tipo do atributo.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE InvoiceEntity i
            SET i.status = :status, i.cnpj = :cnpj, i.amount = :amount, i.issueDate = :issueDate,
                i.cancelled = :cancelled, i.approvedAt = :approvedAt, i.rejectionReason = :rejectionReason,
                i.updatedAt = :updatedAt
            WHERE i.id = :id AND i.status = dev.matheus.invoice.domain.InvoiceStatus.IN_ANALYSIS
            """)
    int updateEnrichment(@Param("id") UUID id, @Param("status") InvoiceStatus status, @Param("cnpj") String cnpj,
            @Param("amount") BigDecimal amount, @Param("issueDate") LocalDate issueDate,
            @Param("cancelled") Boolean cancelled, @Param("approvedAt") Instant approvedAt,
            @Param("rejectionReason") String rejectionReason, @Param("updatedAt") Instant updatedAt);
}
