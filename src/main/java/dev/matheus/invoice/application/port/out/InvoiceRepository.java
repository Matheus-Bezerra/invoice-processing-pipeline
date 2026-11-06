package dev.matheus.invoice.application.port.out;

import java.util.Optional;
import java.util.UUID;

import dev.matheus.invoice.domain.AccessKey;
import dev.matheus.invoice.domain.Invoice;

public interface InvoiceRepository {

    /** Insere a nota se a chave ainda não existir. Devolve {@code false} quando já havia uma nota com a chave. */
    boolean insertIfAbsent(Invoice invoice);

    Optional<Invoice> findById(UUID id);

    Optional<Invoice> findByAccessKey(AccessKey accessKey);
}
