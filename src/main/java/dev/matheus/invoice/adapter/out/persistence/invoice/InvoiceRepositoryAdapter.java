package dev.matheus.invoice.adapter.out.persistence.invoice;

import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import dev.matheus.invoice.application.port.out.persistence.invoice.InvoiceRepository;
import dev.matheus.invoice.domain.AccessKey;
import dev.matheus.invoice.domain.Invoice;
import lombok.RequiredArgsConstructor;

@Repository
@Profile("!webhook")
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

    @Override
    public Optional<Invoice> findByAccessKeyForUpdate(AccessKey accessKey) {
        return jpa.findByAccessKeyForUpdate(accessKey.value()).map(InvoiceMapper::toDomain);
    }

    @Override
    public boolean updateEnrichment(Invoice invoice) {
        return jpa.updateEnrichment(invoice.id(), invoice.status(), invoice.cnpj(), invoice.amount(),
                invoice.issueDate(), invoice.cancelled(), invoice.approvedAt(), invoice.rejectionReason(),
                invoice.updatedAt()) == 1;
    }
}
