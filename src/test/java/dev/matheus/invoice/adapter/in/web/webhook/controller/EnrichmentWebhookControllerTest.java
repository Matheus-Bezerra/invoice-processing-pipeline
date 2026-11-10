package dev.matheus.invoice.adapter.in.web.webhook.controller;

import dev.matheus.invoice.adapter.in.web.webhook.verification.EnrichmentResultParser;
import dev.matheus.invoice.adapter.in.web.webhook.verification.SignatureVerifier;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import dev.matheus.invoice.application.port.out.messaging.ResultPublishFailedException;
import dev.matheus.invoice.application.service.provider.EnrichmentResultPublishingService;
import dev.matheus.invoice.application.service.provider.PublishEnrichmentResultCommand;

/** Nível W: webhook do provedor (H1 a H5). A ordem de verificação é assinatura, payload e publicação. */
@WebMvcTest(value = EnrichmentWebhookController.class, properties = "invoice.webhook.hmac-secret=segredo-de-teste")
@ActiveProfiles("webhook")
@Import({SignatureVerifier.class, EnrichmentResultParser.class})
class EnrichmentWebhookControllerTest {

    private static final String SECRET = "segredo-de-teste";
    private static final String URL = "/webhooks/enrichment-results";
    private static final UUID MESSAGE_ID = UUID.fromString("c3b2f2a1-5d6e-3f7a-8b9c-1e2d3c4b5a69");
    private static final UUID CORRELATION_ID = UUID.fromString("0b6f5c1e-8d2a-4c53-9a43-6f4b6a0f2f11");
    private static final String KEY = "35260112345678000199550010000012341000012345";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EnrichmentResultPublishingService service;

    private static String valid() {
        return """
                {"messageId":"%s","eventType":"invoice.enrichment.completed","occurredAt":"2026-11-04T12:00:02Z",
                 "correlationId":"%s","payload":{"accessKey":"%s","cnpj":"12ABC34501DE35","amount":1500.00,
                 "issueDate":"2026-01-10","cancelled":false}}
                """.formatted(MESSAGE_ID, CORRELATION_ID, KEY);
    }

    private static String envelope(String payload) {
        return "{\"messageId\":\"%s\",\"correlationId\":\"%s\",\"payload\":%s}".formatted(MESSAGE_ID, CORRELATION_ID, payload);
    }

    private static String sign(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private ResultActions call(String body, String signature) throws Exception {
        var request = post(URL).contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8));
        if (signature != null) {
            request.header("X-Signature", signature);
        }
        return mockMvc.perform(request);
    }

    private ResultActions callSigned(String body) throws Exception {
        return call(body, sign(body, SECRET));
    }

    @Test
    void h1_validSignatureAndResultIsPublishedAndAccepted() throws Exception {
        String body = valid();

        callSigned(body).andExpect(status().isAccepted()).andExpect(content().string(""));

        verify(service).publish(org.mockito.ArgumentMatchers.argThat(command ->
                command.messageId().equals(MESSAGE_ID)
                        && command.correlationId().equals(CORRELATION_ID)
                        && new String(command.body(), StandardCharsets.UTF_8).equals(body)));
    }

    @Test
    void h1_incompleteButWellFormedResultIsAcceptedBecauseTheWebhookDoesNotApplyBusinessRules() throws Exception {
        callSigned(envelope("{\"accessKey\":\"" + KEY + "\",\"cnpj\":\"invalido\",\"amount\":-5}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void h2_missingSignatureIs401WithoutPublishing() throws Exception {
        call(valid(), null)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Assinatura inválida"));

        verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "",
            "abc",
            "md5=0123456789abcdef",
            "sha256=",
            "sha256=zz",
            "sha256=0123",
            "sha256=ZZZZ0000ZZZZ0000ZZZZ0000ZZZZ0000ZZZZ0000ZZZZ0000ZZZZ0000ZZZZ0000"
    })
    void h2_malformedSignatureIs401WithoutPublishing(String signature) throws Exception {
        call(valid(), signature).andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void h3_bodyChangedAfterSigningIs401WithoutPublishing() throws Exception {
        String signature = sign(valid(), SECRET);

        call(valid().replace("1500.00", "9999.00"), signature).andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void h3_signatureMadeWithAnotherSecretIs401() throws Exception {
        call(valid(), sign(valid(), "outro-segredo")).andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void signatureIsCheckedBeforeThePayload() throws Exception {
        call("isto nao e json", "sha256=" + "0".repeat(64)).andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "isto nao e json",
            "",
            "[]",
            "{}",
            "{\"payload\":{\"accessKey\":\"35260112345678000199550010000012341000012345\"}}",
            "{\"messageId\":\"nao-e-uuid\",\"payload\":{\"accessKey\":\"35260112345678000199550010000012341000012345\"}}",
            "{\"messageId\":\"c3b2f2a1-5d6e-3f7a-8b9c-1e2d3c4b5a69\"}",
            "{\"messageId\":\"c3b2f2a1-5d6e-3f7a-8b9c-1e2d3c4b5a69\",\"payload\":{}}",
            "{\"messageId\":\"c3b2f2a1-5d6e-3f7a-8b9c-1e2d3c4b5a69\",\"payload\":{\"accessKey\":\"123\"}}",
            "{\"messageId\":\"c3b2f2a1-5d6e-3f7a-8b9c-1e2d3c4b5a69\",\"payload\":{\"accessKey\":\"35260112345678000199550010000012341000012345\",\"amount\":\"abc\"}}",
            "{\"messageId\":\"c3b2f2a1-5d6e-3f7a-8b9c-1e2d3c4b5a69\",\"payload\":{\"accessKey\":\"35260112345678000199550010000012341000012345\",\"issueDate\":\"2026-02-30\"}}",
            "{\"messageId\":\"c3b2f2a1-5d6e-3f7a-8b9c-1e2d3c4b5a69\",\"payload\":{\"accessKey\":\"35260112345678000199550010000012341000012345\",\"cancelled\":\"talvez\"}}"
    })
    void h4_signedButMalformedPayloadIs400WithoutPublishing(String body) throws Exception {
        callSigned(body)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Resultado malformado"));

        verifyNoInteractions(service);
    }

    @Test
    void h4_errorDetailDoesNotEchoTheReceivedValue() throws Exception {
        callSigned(envelope("{\"accessKey\":\"" + KEY + "\",\"amount\":\"valor-secreto-123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("valor-secreto-123"))));
    }

    @Test
    void h5_publicationFailureIs503() throws Exception {
        doThrow(new ResultPublishFailedException("sem confirmação", null)).when(service)
                .publish(any(PublishEnrichmentResultCommand.class));

        callSigned(valid())
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Publicação indisponível"));
    }

    @Test
    void invalidSignatureNeverReachesThePublisher() throws Exception {
        call(valid(), "sha256=" + "a".repeat(64)).andExpect(status().isUnauthorized());

        verify(service, never()).publish(any());
    }
}
