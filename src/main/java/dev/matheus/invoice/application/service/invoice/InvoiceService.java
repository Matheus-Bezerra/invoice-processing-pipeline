package dev.matheus.invoice.application.service.invoice;

import dev.matheus.invoice.application.service.outbox.OutboxPublisherService;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import dev.matheus.invoice.application.message.EnrichmentRequestMessage;
import dev.matheus.invoice.application.port.out.clock.ClockPort;
import dev.matheus.invoice.application.port.out.persistence.invoice.InvoiceRepository;
import dev.matheus.invoice.application.port.out.persistence.outbox.OutboxPort;
import dev.matheus.invoice.domain.AccessKey;
import dev.matheus.invoice.domain.Invoice;
import dev.matheus.invoice.domain.exception.InvoiceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
@Profile("!webhook")
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoices;
    private final OutboxPort outbox;
    private final ClockPort clock;
    private final JsonMapper jsonMapper;
    private final OutboxPublisherService outboxPublisher;
    private final ExecutorService immediatePublishExecutor;

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
        log.info("Nota cadastrada em análise: correlationId={}, messageId={}", invoice.id(), messageId);
        publishAfterCommit(messageId, invoice.id());

        return new CreateInvoiceResult(invoice, true);
    }

    /**
     * Publicação imediata, só depois do commit real da transação que gravou a nota e o outbox. Se a transação
     * fizer rollback, nada é publicado. A publicação roda em outra thread, então o request não espera o broker.
     * Qualquer falha (ou fila do executor cheia) só vai para o log: a linha continua no outbox e o relay do
     * worker publica depois.
     */
    private void publishAfterCommit(UUID messageId, UUID invoiceId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    immediatePublishExecutor.execute(() -> publishQuietly(messageId, invoiceId));
                } catch (RejectedExecutionException e) {
                    log.warn("Publicação imediata descartada, o relay vai publicar: correlationId={}, messageId={}",
                            invoiceId, messageId);
                }
            }
        });
    }

    private void publishQuietly(UUID messageId, UUID invoiceId) {
        try {
            outboxPublisher.publish(messageId);
        } catch (RuntimeException e) {
            log.warn("Publicação imediata falhou, o relay vai tentar de novo: correlationId={}, messageId={}, erro={}",
                    invoiceId, messageId, e.getMessage());
        }
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
