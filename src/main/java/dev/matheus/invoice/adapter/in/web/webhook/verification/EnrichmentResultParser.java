package dev.matheus.invoice.adapter.in.web.webhook.verification;

import dev.matheus.invoice.adapter.in.web.webhook.dto.EnrichmentResultRequest;
import dev.matheus.invoice.adapter.in.web.webhook.exception.MalformedResultException;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import dev.matheus.invoice.domain.AccessKey;
import dev.matheus.invoice.domain.exception.InvalidAccessKeyException;
import dev.matheus.invoice.shared.JsonReadFailure;
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
public class EnrichmentResultParser {

    /** Ids lidos do corpo, para as propriedades da mensagem. {@code correlationId} pode ser nulo. */
    public record ParsedResult(UUID messageId, UUID correlationId) {
    }

    private final JsonMapper jsonMapper;

    public ParsedResult parse(byte[] body) {
        EnrichmentResultRequest result;
        try {
            result = jsonMapper.readValue(body, EnrichmentResultRequest.class);
        } catch (JacksonException e) {
            throw new MalformedResultException(JsonReadFailure.describe(e));
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

}
