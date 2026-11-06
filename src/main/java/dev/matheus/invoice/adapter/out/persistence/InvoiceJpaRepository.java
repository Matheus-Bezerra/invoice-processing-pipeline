package dev.matheus.invoice.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
