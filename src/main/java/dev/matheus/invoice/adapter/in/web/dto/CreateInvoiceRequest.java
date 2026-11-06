package dev.matheus.invoice.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Dados para cadastrar uma nota fiscal")
public record CreateInvoiceRequest(
        @Schema(description = "Chave de acesso da nota: exatamente 44 dígitos (espaços nas pontas são ignorados)",
                example = "35260112345678000199550010000012341000012345") String accessKey) {
}
