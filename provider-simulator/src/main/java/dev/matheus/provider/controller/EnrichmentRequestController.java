package dev.matheus.provider.controller;

import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import dev.matheus.provider.model.EnrichmentRequest;
import dev.matheus.provider.service.RequestDeduplicator;
import dev.matheus.provider.service.WebhookCallbackSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /enrichment-requests} (spec, seções 3.4 e 6): valida o corpo, deduplica pelo {@code messageId} e
 * responde {@code 202} sem dados. O resultado vai depois, pelo callback assinado. Corpo inválido recebe {@code 400}
 * em {@code problem+json}, só com o caminho do campo (o valor recebido não vai para a resposta nem para o log).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
class EnrichmentRequestController {

    private static final Pattern ACCESS_KEY = Pattern.compile("\\d{44}");

    private final JsonMapper jsonMapper;
    private final RequestDeduplicator deduplicator;
    private final WebhookCallbackSender callbackSender;

    @PostMapping(path = "/enrichment-requests", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> receive(@RequestBody(required = false) byte[] body,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationHeader) {
        EnrichmentRequest request;
        try {
            request = parse(body);
        } catch (InvalidRequestException e) {
            log.warn("Solicitação inválida recusada: motivo={}, correlationId={}", e.getMessage(), correlationHeader);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
            problem.setTitle("Solicitação inválida");
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
        }

        if (deduplicator.firstTime(request.messageId())) {
            callbackSender.scheduleApprovableResult(request);
            log.info("Solicitação aceita, callback agendado: messageId={}, correlationId={}",
                    request.messageId(), request.effectiveCorrelationId());
        } else {
            log.info("Solicitação duplicada ignorada: messageId={}, correlationId={}",
                    request.messageId(), request.effectiveCorrelationId());
        }
        return ResponseEntity.accepted().build();
    }

    private EnrichmentRequest parse(byte[] body) {
        if (body == null || body.length == 0) {
            throw new InvalidRequestException("corpo ausente");
        }
        EnrichmentRequest request;
        try {
            request = jsonMapper.readValue(body, EnrichmentRequest.class);
        } catch (JacksonException e) {
            throw new InvalidRequestException(readFailure(e));
        }
        if (request == null || request.messageId() == null) {
            throw new InvalidRequestException("messageId ausente");
        }
        if (request.payload() == null || request.payload().invoiceId() == null) {
            throw new InvalidRequestException("payload.invoiceId ausente");
        }
        if (request.payload().accessKey() == null) {
            throw new InvalidRequestException("payload.accessKey ausente");
        }
        if (!ACCESS_KEY.matcher(request.payload().accessKey()).matches()) {
            throw new InvalidRequestException("payload.accessKey fora do formato de 44 dígitos");
        }
        return request;
    }

    /** Só o caminho do campo: a mensagem do Jackson traz o valor recebido. */
    private static String readFailure(JacksonException e) {
        String path = e.getPath().stream()
                .map(JacksonException.Reference::getPropertyName)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("."));
        return path.isEmpty() ? "JSON inválido" : "campo com tipo ou formato ilegível: " + path;
    }

    private static final class InvalidRequestException extends RuntimeException {
        InvalidRequestException(String message) {
            super(message);
        }
    }
}
