package dev.matheus.invoice.application.service.invoice;

import dev.matheus.invoice.domain.Invoice;

/** Resultado do cadastro: {@code created} é falso quando a chave já existia. */
public record CreateInvoiceResult(Invoice invoice, boolean created) {
}
