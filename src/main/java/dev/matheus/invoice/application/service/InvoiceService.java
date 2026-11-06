package dev.matheus.invoice.application.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.matheus.invoice.application.message.EnrichmentRequestMessage;
import dev.matheus.invoice.application.port.out.ClockPort;
import dev.matheus.invoice.application.port.out.InvoiceRepository;
import dev.matheus.invoice.application.port.out.OutboxPort;
import dev.matheus.invoice.domain.AccessKey;
import dev.matheus.invoice.domain.Invoice;
import dev.matheus.invoice.domain.exception.InvoiceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoices;
    private final OutboxPort outbox;
    private final ClockPort clock;
    private final JsonMapper jsonMapper;

    /**
     * Cadastra a nota e a solicitação do outbox na mesma transação. A unicidade vem do banco; se a chave já
     * existir, devolve a nota existente e não grava nada no outbox.
     */
    @Transactional
    public CreateInvoiceResult create(AccessKey accessKey) {
        Instant now = clock.now();
        Invoice invoice = Invoice.create(accessKey, now);
        
        if (!invoices.insertIfAbsent(invoice)) {
            Invoice existing = invoices.findByAccessKey(accessKey).orElseThrow();
            log.info("Chave já cadastrada, devolvendo a nota existente: invoiceId={}", existing.id());
            return new CreateInvoiceResult(existing, false);
        }

        UUID messageId = UUID.randomUUID();
        String payload = jsonMapper.writeValueAsString(
                EnrichmentRequestMessage.of(messageId, invoice.id(), accessKey.value(), now));

        outbox.enqueue(messageId, invoice.id(), accessKey, payload, now);
        log.info("Nota cadastrada em análise: invoiceId={}, messageId={}", invoice.id(), messageId);

        return new CreateInvoiceResult(invoice, true);
    }

    @Transactional(readOnly = true)
    public Invoice getById(UUID id) {
        return invoices.findById(id).orElseThrow(InvoiceNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public Invoice getByAccessKey(AccessKey accessKey) {
        return invoices.findByAccessKey(accessKey).orElseThrow(InvoiceNotFoundException::new);
    }
}
