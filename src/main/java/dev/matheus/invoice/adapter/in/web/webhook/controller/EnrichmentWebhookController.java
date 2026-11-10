package dev.matheus.invoice.adapter.in.web.webhook.controller;

import dev.matheus.invoice.adapter.in.web.webhook.api.EnrichmentWebhookApi;
import dev.matheus.invoice.adapter.in.web.webhook.verification.EnrichmentResultParser;
import dev.matheus.invoice.adapter.in.web.webhook.verification.LimitedBodyReader;
import dev.matheus.invoice.adapter.in.web.webhook.verification.SignatureVerifier;
import java.io.InputStream;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import dev.matheus.invoice.application.service.provider.EnrichmentResultPublishingService;
import dev.matheus.invoice.application.service.provider.PublishEnrichmentResultCommand;
import lombok.RequiredArgsConstructor;

/** Ordem de verificação (spec, seção 2.5): tamanho do corpo, assinatura, payload e publicação. */
@RestController
@Profile("webhook")
@RequiredArgsConstructor
class EnrichmentWebhookController implements EnrichmentWebhookApi {

    private final LimitedBodyReader bodyReader;
    private final SignatureVerifier signatureVerifier;
    private final EnrichmentResultParser parser;
    private final EnrichmentResultPublishingService service;

    @Override
    public ResponseEntity<Void> receive(InputStream body, String signature) {
        byte[] raw = bodyReader.read(body);
        signatureVerifier.verify(signature, raw);
        EnrichmentResultParser.ParsedResult result = parser.parse(raw);
        service.publish(new PublishEnrichmentResultCommand(result.messageId(), result.correlationId(), raw));
        return ResponseEntity.accepted().build();
    }
}
