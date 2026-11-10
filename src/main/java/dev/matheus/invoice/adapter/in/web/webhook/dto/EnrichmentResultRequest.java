package dev.matheus.invoice.adapter.in.web.webhook.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Envelope do resultado enviado pelo provedor (spec, seção 3.2). {@code cancelled} é {@code Boolean}, nunca
 * {@code boolean}: com o primitivo, um campo ausente viraria {@code false}. O webhook só confere o formato.
 */
@Schema(description = "Resultado do enriquecimento enviado pelo provedor")
public record EnrichmentResultRequest(
        @Schema(description = "Identificador da mensagem. É derivado do identificador da solicitação, então reenvios "
                + "do mesmo callback repetem o valor", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID messageId,
        @Schema(description = "Tipo do evento", example = "invoice.enrichment.completed")
        String eventType,
        @Schema(description = "Instante em que o resultado foi gerado (UTC)")
        Instant occurredAt,
        @Schema(description = "Identificador de rastreio, copiado da solicitação")
        UUID correlationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Payload payload) {

    @Schema(description = "Dados da nota. O webhook não valida regras de negócio: a decisão é do consumer")
    public record Payload(
            @Schema(description = "Chave de acesso de 44 dígitos", example = "35260112345678000199550010000012341000012345",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String accessKey,
            @Schema(description = "CNPJ do emitente. Ausente indica resultado incompleto", example = "12ABC34501DE35")
            String cnpj,
            @Schema(description = "Valor da nota. Ausente indica resultado incompleto", example = "1500.00")
            BigDecimal amount,
            @Schema(description = "Data de emissão (yyyy-MM-dd). Ausente indica resultado incompleto", example = "2026-01-10")
            LocalDate issueDate,
            @Schema(description = "Nota cancelada. Ausente indica resultado incompleto")
            Boolean cancelled) {
    }
}
