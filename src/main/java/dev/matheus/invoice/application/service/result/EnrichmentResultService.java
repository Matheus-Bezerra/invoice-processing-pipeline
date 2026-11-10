package dev.matheus.invoice.application.service.result;

import java.time.Instant;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.matheus.invoice.application.port.out.ClockPort;
import dev.matheus.invoice.application.port.out.InvoiceRepository;
import dev.matheus.invoice.application.port.out.ProcessedMessageStore;
import dev.matheus.invoice.domain.Invoice;
import dev.matheus.invoice.domain.ProcessingOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Processa o resultado do provedor numa única transação: trava a nota ({@code FOR UPDATE}), registra o
 * {@code messageId} em {@code processed_messages} e só então decide. Qualquer exceção desfaz tudo, inclusive o
 * registro do {@code messageId}, e o consumer faz o retry.
 */
@Slf4j
@Service
@Profile("!webhook")
@RequiredArgsConstructor
public class EnrichmentResultService {

    private final InvoiceRepository invoices;
    private final ProcessedMessageStore processedMessages;
    private final ClockPort clock;

    @Transactional
    public ResultHandling process(EnrichmentResultCommand command) {
        Invoice invoice = invoices.findByAccessKeyForUpdate(command.accessKey())
                .orElseThrow(UnknownAccessKeyException::new);
        Instant now = clock.now();

        if (!processedMessages.markProcessed(command.messageId(), now)) {
            log.info("Resultado duplicado ignorado: correlationId={}, messageId={}", invoice.id(), command.messageId());
            return ResultHandling.DUPLICATE;
        }

        ProcessingOutcome outcome = invoice.applyEnrichment(command.data(), now);
        
        if (outcome == ProcessingOutcome.IGNORED_ALREADY_FINAL || !invoices.updateEnrichment(invoice)) {
            log.info("Nota já finalizada, resultado ignorado: correlationId={}, messageId={}, status={}",
                    invoice.id(), command.messageId(), invoice.status());
            return ResultHandling.IGNORED_ALREADY_FINAL;
        }

        if (outcome == ProcessingOutcome.APPROVED) {
            log.info("Nota aprovada: correlationId={}, messageId={}", invoice.id(), command.messageId());
            return ResultHandling.APPROVED;
        }

        log.info("Nota rejeitada: correlationId={}, messageId={}, motivo={}",
                invoice.id(), command.messageId(), invoice.rejectionReason());
        return ResultHandling.REJECTED;
    }
}
