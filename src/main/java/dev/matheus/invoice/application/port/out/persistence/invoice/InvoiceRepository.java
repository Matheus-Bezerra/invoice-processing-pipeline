package dev.matheus.invoice.application.port.out.persistence.invoice;

import java.util.Optional;
import java.util.UUID;

import dev.matheus.invoice.domain.AccessKey;
import dev.matheus.invoice.domain.Invoice;

public interface InvoiceRepository {

    /** Insere a nota se a chave ainda não existir. Devolve {@code false} quando já havia uma nota com a chave. */
    boolean insertIfAbsent(Invoice invoice);

    Optional<Invoice> findById(UUID id);

    Optional<Invoice> findByAccessKey(AccessKey accessKey);

    /** Lê a nota e trava a linha ({@code FOR UPDATE}) até o fim da transação em andamento. */
    Optional<Invoice> findByAccessKeyForUpdate(AccessKey accessKey);

    /**
     * Grava a decisão do enriquecimento (status, dados, {@code approvedAt}, motivo). Só altera a linha se ela ainda
     * estiver em {@code IN_ANALYSIS} (guarda de estado final). Devolve {@code false} quando nada foi alterado.
     */
    boolean updateEnrichment(Invoice invoice);
}
