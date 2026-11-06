package dev.matheus.invoice.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import dev.matheus.invoice.application.port.out.InvoiceRepository;
import dev.matheus.invoice.domain.AccessKey;
import dev.matheus.invoice.domain.Invoice;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
class InvoiceRepositoryAdapter implements InvoiceRepository {

    private final InvoiceJpaRepository jpa;

    @Override
    public boolean insertIfAbsent(Invoice invoice) {
        return jpa.insertIfAbsent(invoice.id(), invoice.accessKey().value(), invoice.status().name(),
                invoice.createdAt(), invoice.updatedAt()) == 1;
    }

    @Override
    public Optional<Invoice> findById(UUID id) {
        return jpa.findById(id).map(InvoiceMapper::toDomain);
    }

    @Override
    public Optional<Invoice> findByAccessKey(AccessKey accessKey) {
        return jpa.findByAccessKey(accessKey.value()).map(InvoiceMapper::toDomain);
    }
}
