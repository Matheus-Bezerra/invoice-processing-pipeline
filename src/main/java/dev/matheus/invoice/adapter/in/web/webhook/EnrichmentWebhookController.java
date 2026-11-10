package dev.matheus.invoice.adapter.in.web.webhook;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import dev.matheus.invoice.application.service.EnrichmentResultPublishingService;
import dev.matheus.invoice.application.service.PublishEnrichmentResultCommand;
import lombok.RequiredArgsConstructor;

/** Ordem de verificação (spec, seção 2.5): assinatura, depois payload, depois publicação. */
@RestController
@Profile("webhook")
@RequiredArgsConstructor
class EnrichmentWebhookController implements EnrichmentWebhookApi {

    private final SignatureVerifier signatureVerifier;
    private final EnrichmentResultParser parser;
    private final EnrichmentResultPublishingService service;

    @Override
    public ResponseEntity<Void> receive(byte[] body, String signature) {
        byte[] raw = body == null ? new byte[0] : body;
        signatureVerifier.verify(signature, raw);
        EnrichmentResultParser.ParsedResult result = parser.parse(raw);
        service.publish(new PublishEnrichmentResultCommand(result.messageId(), result.correlationId(), raw));
        return ResponseEntity.accepted().build();
    }
}
