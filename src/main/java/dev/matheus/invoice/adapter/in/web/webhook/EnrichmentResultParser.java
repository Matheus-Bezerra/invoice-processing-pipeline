package dev.matheus.invoice.adapter.in.web.webhook;

import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import dev.matheus.invoice.domain.AccessKey;
import dev.matheus.invoice.domain.exception.InvalidAccessKeyException;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Confere só o formato do resultado (spec, seções 2.5 e 3.2): JSON válido, {@code messageId} e
 * {@code payload.accessKey} presentes, chave de 44 dígitos e campos legíveis. Nada de regra de negócio.
 */
@Component
@Profile("webhook")
@RequiredArgsConstructor
class EnrichmentResultParser {

    /** Ids lidos do corpo, para as propriedades da mensagem. {@code correlationId} pode ser nulo. */
    record ParsedResult(UUID messageId, UUID correlationId) {
    }

    private final JsonMapper jsonMapper;

    ParsedResult parse(byte[] body) {
        EnrichmentResultRequest result;
        try {
            result = jsonMapper.readValue(body, EnrichmentResultRequest.class);
        } catch (JacksonException e) {
            throw new MalformedResultException(readFailure(e));
        }
        if (result == null || result.messageId() == null) {
            throw new MalformedResultException("messageId ausente");
        }
        EnrichmentResultRequest.Payload payload = result.payload();
        if (payload == null || payload.accessKey() == null) {
            throw new MalformedResultException("payload.accessKey ausente");
        }
        try {
            AccessKey.of(payload.accessKey());
        } catch (InvalidAccessKeyException e) {
            throw new MalformedResultException("payload.accessKey fora do formato de 44 dígitos");
        }
        return new ParsedResult(result.messageId(), result.correlationId());
    }

    /** Só o caminho do campo: a mensagem do Jackson traz o valor recebido, que não pode ir para a resposta nem para o log. */
    private static String readFailure(JacksonException e) {
        String path = e.getPath().stream()
                .map(JacksonException.Reference::getPropertyName)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("."));
        return path.isEmpty() ? "JSON inválido" : "campo com tipo ou formato ilegível: " + path;
    }
}
