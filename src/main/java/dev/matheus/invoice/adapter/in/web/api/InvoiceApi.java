package dev.matheus.invoice.adapter.in.web.api;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import dev.matheus.invoice.adapter.in.web.dto.CreateInvoiceRequest;
import dev.matheus.invoice.adapter.in.web.dto.InvoiceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Notas fiscais", description = "Cadastro e consulta de notas fiscais")
@RequestMapping("/invoices")
public interface InvoiceApi {

    @Operation(summary = "Cadastra uma nota fiscal",
            description = "Grava a nota em análise e registra a solicitação de enriquecimento. "
                    + "A nota nunca é aprovada nesta chamada: o resultado chega depois, de forma assíncrona. "
                    + "Se a chave já estiver cadastrada, devolve a nota existente.")
    @ApiResponse(responseCode = "201", description = "Nota cadastrada e em análise",
            content = @Content(schema = @Schema(implementation = InvoiceResponse.class)))
    @ApiResponse(responseCode = "200", description = "Chave já cadastrada: devolve a nota existente, no estado atual",
            content = @Content(schema = @Schema(implementation = InvoiceResponse.class)))
    @ApiResponse(responseCode = "400", description = "Chave de acesso ausente, fora do formato ou corpo inválido",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<InvoiceResponse> create(@RequestBody CreateInvoiceRequest request);

    @Operation(summary = "Consulta uma nota pelo identificador")
    @ApiResponse(responseCode = "200", description = "Nota encontrada",
            content = @Content(schema = @Schema(implementation = InvoiceResponse.class)))
    @ApiResponse(responseCode = "400", description = "Identificador que não é um UUID",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Nota não encontrada",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    InvoiceResponse getById(@Parameter(description = "Identificador da nota") @PathVariable UUID id);

    @Operation(summary = "Consulta uma nota pela chave de acesso")
    @ApiResponse(responseCode = "200", description = "Nota encontrada",
            content = @Content(schema = @Schema(implementation = InvoiceResponse.class)))
    @ApiResponse(responseCode = "400", description = "Chave de acesso ausente ou fora do formato",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Nota não encontrada",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    InvoiceResponse getByAccessKey(
            @Parameter(description = "Chave de acesso de 44 dígitos") @RequestParam String accessKey);
}
