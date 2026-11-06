package dev.matheus.invoice.adapter.in.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import dev.matheus.invoice.domain.Invoice;
import dev.matheus.invoice.domain.InvoiceStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Nota fiscal e o estado do seu processamento")
public record InvoiceResponse(
        @Schema(description = "Identificador da nota") UUID id,
        @Schema(description = "Chave de acesso de 44 dígitos") String accessKey,
        @Schema(description = "Estado da nota") InvoiceStatus status,
        @Schema(description = "CNPJ do emitente. Nulo até o resultado do provedor chegar") String cnpj,
        @Schema(description = "Valor da nota. Nulo até o resultado do provedor chegar") BigDecimal amount,
        @Schema(description = "Data de emissão. Nula até o resultado do provedor chegar") LocalDate issueDate,
        @Schema(description = "Indica se a nota foi cancelada. Nulo até o resultado do provedor chegar") Boolean cancelled,
        @Schema(description = "Horário da aprovação (UTC). Nulo se a nota não foi aprovada") Instant approvedAt,
        @Schema(description = "Motivo da rejeição. Nulo se a nota não foi rejeitada") String rejectionReason,
        @Schema(description = "Horário do cadastro (UTC)") Instant createdAt) {

    public static InvoiceResponse from(Invoice invoice) {
        return new InvoiceResponse(invoice.id(), invoice.accessKey().value(), invoice.status(), invoice.cnpj(),
                invoice.amount(), invoice.issueDate(), invoice.cancelled(), invoice.approvedAt(),
                invoice.rejectionReason(), invoice.createdAt());
    }
}
