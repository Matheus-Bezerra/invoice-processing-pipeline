package dev.matheus.invoice.adapter.in.web.webhook.api;

import dev.matheus.invoice.adapter.in.web.webhook.dto.EnrichmentResultRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Webhook do provedor", description = "Recebe o resultado do enriquecimento enviado pelo provedor")
@RequestMapping("/webhooks")
public interface EnrichmentWebhookApi {

    @Operation(summary = "Recebe o resultado do enriquecimento",
            description = "Só o provedor chama este endpoint. A assinatura HMAC-SHA256 do corpo bruto é conferida "
                    + "primeiro, depois o formato do resultado, e só então ele é publicado na fila e confirmado "
                    + "pelo broker. O webhook não aplica regras de negócio nem acessa o banco: aprovar ou rejeitar "
                    + "a nota é do consumer do resultado. O mesmo callback recebido duas vezes é publicado duas "
                    + "vezes, e o consumer descarta a duplicata.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Envelope do resultado, assinado pelo provedor",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = EnrichmentResultRequest.class))))
    @ApiResponse(responseCode = "202", description = "Resultado publicado e confirmado pelo broker")
    @ApiResponse(responseCode = "400", description = "Resultado malformado: JSON inválido, messageId ou "
            + "payload.accessKey ausentes ou com formato inválido, ou campo ilegível. Nada é publicado",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Cabeçalho X-Signature ausente, mal formado ou que não confere "
            + "com o corpo. Nada é publicado",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "O broker não confirmou a publicação (nack, timeout ou "
            + "mensagem devolvida). O provedor deve reenviar o callback",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(path = "/enrichment-results", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> receive(
            @RequestBody(required = false) byte[] body,
            @Parameter(description = "Assinatura do corpo bruto: sha256=<HMAC-SHA256 em hexadecimal, 64 caracteres> "
                    + "calculado com o segredo compartilhado", example = "sha256=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
            @RequestHeader(name = "X-Signature", required = false) String signature);
}
