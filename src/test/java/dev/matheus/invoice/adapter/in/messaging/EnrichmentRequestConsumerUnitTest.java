package dev.matheus.invoice.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;

import dev.matheus.invoice.application.port.out.provider.ProviderRejectedException;
import dev.matheus.invoice.application.port.out.provider.ProviderUnavailableException;
import dev.matheus.invoice.application.service.provider.EnrichmentRequestForwardingService;
import dev.matheus.invoice.application.service.provider.ForwardEnrichmentRequestCommand;
import tools.jackson.databind.json.JsonMapper;

/** Nível A: consumer da solicitação, sem Spring (C1 a C4). */
class EnrichmentRequestConsumerUnitTest {

    private static final UUID MESSAGE_ID = UUID.randomUUID();
    private static final UUID INVOICE_ID = UUID.randomUUID();
    private static final UUID CORRELATION_ID = UUID.randomUUID();
    private static final String KEY = "35260112345678000199550010000012341000012345";

    private EnrichmentRequestForwardingService service;
    private EnrichmentRequestConsumer consumer;

    @BeforeEach
    void setUp() {
        service = mock(EnrichmentRequestForwardingService.class);
        consumer = new EnrichmentRequestConsumer(service, JsonMapper.builder().build());
    }

    private static Message message(String json) {
        return MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8)).build();
    }

    private static String valid(String correlationId) {
        return """
                {"messageId":"%s","eventType":"invoice.enrichment.requested","occurredAt":"2026-11-04T12:00:00Z",
                 "correlationId":%s,"payload":{"invoiceId":"%s","accessKey":"%s"}}
                """.formatted(MESSAGE_ID, correlationId, INVOICE_ID, KEY);
    }

    @Test
    void c1_forwardsTheRawBodyWithTheCorrelationId() {
        String json = valid("\"" + CORRELATION_ID + "\"");

        consumer.onRequest(message(json));

        verify(service).forward(new ForwardEnrichmentRequestCommand(MESSAGE_ID, CORRELATION_ID, json));
    }

    @Test
    void c1_withoutCorrelationIdUsesTheInvoiceId() {
        String json = valid("null");

        consumer.onRequest(message(json));

        verify(service).forward(new ForwardEnrichmentRequestCommand(MESSAGE_ID, INVOICE_ID, json));
    }

    @Test
    void c2_providerRejectionGoesStraightToTheDeadLetterQueue() {
        doThrow(new ProviderRejectedException(400)).when(service).forward(any());

        assertThatThrownBy(() -> consumer.onRequest(message(valid("null"))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    void c3_technicalFailureIsNotWrappedSoTheContainerRetries() {
        doThrow(new ProviderUnavailableException("fora do ar", null)).when(service).forward(any());

        assertThatThrownBy(() -> consumer.onRequest(message(valid("null"))))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "isto nao e json",
            "{}",
            "{\"messageId\":\"6a1f0c58-3f2e-4b8e-9d1b-0d6b6e2c9a10\"}",
            "{\"messageId\":\"6a1f0c58-3f2e-4b8e-9d1b-0d6b6e2c9a10\",\"payload\":{\"accessKey\":\"35260112345678000199550010000012341000012345\"}}",
            "{\"messageId\":\"6a1f0c58-3f2e-4b8e-9d1b-0d6b6e2c9a10\",\"payload\":{\"invoiceId\":\"0b6f5c1e-8d2a-4c53-9a43-6f4b6a0f2f11\"}}",
            "{\"payload\":{\"invoiceId\":\"0b6f5c1e-8d2a-4c53-9a43-6f4b6a0f2f11\",\"accessKey\":\"35260112345678000199550010000012341000012345\"}}",
            "{\"messageId\":\"nao-e-uuid\",\"payload\":{\"invoiceId\":\"0b6f5c1e-8d2a-4c53-9a43-6f4b6a0f2f11\",\"accessKey\":\"35260112345678000199550010000012341000012345\"}}"
    })
    void c4_malformedRequestGoesToTheDeadLetterQueueWithoutCallingTheProvider(String body) {
        assertThatThrownBy(() -> consumer.onRequest(message(body)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verify(service, never()).forward(any());
    }
}
