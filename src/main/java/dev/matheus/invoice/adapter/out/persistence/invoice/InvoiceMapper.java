package dev.matheus.invoice.adapter.out.persistence.invoice;

import dev.matheus.invoice.domain.AccessKey;
import dev.matheus.invoice.domain.Invoice;

final class InvoiceMapper {

    private InvoiceMapper() {
    }

    static Invoice toDomain(InvoiceEntity entity) {
        return Invoice.restore(entity.getId(), new AccessKey(entity.getAccessKey()), entity.getStatus(),
                entity.getCnpj(), entity.getAmount(), entity.getIssueDate(), entity.getCancelled(),
                entity.getApprovedAt(), entity.getRejectionReason(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
