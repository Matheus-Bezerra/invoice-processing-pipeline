package dev.matheus.invoice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.matheus.invoice.application.port.out.ClockPort;
import dev.matheus.invoice.application.port.out.InvoiceRepository;
import dev.matheus.invoice.application.port.out.OutboxPort;
import dev.matheus.invoice.domain.AccessKey;
import dev.matheus.invoice.domain.Invoice;
import dev.matheus.invoice.domain.InvoiceStatus;
import dev.matheus.invoice.domain.exception.InvoiceNotFoundException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    private static final AccessKey KEY = AccessKey.of("35260112345678000199550010000012341000012345");
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

    @Mock
    private InvoiceRepository invoices;
    @Mock
    private OutboxPort outbox;
    @Mock
    private ClockPort clock;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private InvoiceService service;

    @BeforeEach
    void setUp() {
        service = new InvoiceService(invoices, outbox, clock, jsonMapper);
    }

    @Test
    void t2_newInvoiceStaysInAnalysisWithoutApprovalWhileNoResultExists() {
        when(clock.now()).thenReturn(NOW);
        when(invoices.insertIfAbsent(any())).thenReturn(true);

        CreateInvoiceResult result = service.create(KEY);

        assertThat(result.created()).isTrue();
        assertThat(result.invoice().status()).isEqualTo(InvoiceStatus.IN_ANALYSIS);
        assertThat(result.invoice().approvedAt()).isNull();
        assertThat(result.invoice().createdAt()).isEqualTo(NOW);
        ArgumentCaptor<Invoice> saved = ArgumentCaptor.forClass(Invoice.class);
        verify(invoices).insertIfAbsent(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(InvoiceStatus.IN_ANALYSIS);
    }

    @Test
    void newInvoiceEnqueuesTheRequestEnvelopeWithTheOutboxIdAsMessageId() {
        when(clock.now()).thenReturn(NOW);
        when(invoices.insertIfAbsent(any())).thenReturn(true);

        Invoice invoice = service.create(KEY).invoice();

        ArgumentCaptor<UUID> outboxId = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(outboxId.capture(), org.mockito.ArgumentMatchers.eq(invoice.id()),
                org.mockito.ArgumentMatchers.eq(KEY), payload.capture(), org.mockito.ArgumentMatchers.eq(NOW));
        JsonNode json = jsonMapper.readTree(payload.getValue());
        assertThat(json.get("messageId").asString()).isEqualTo(outboxId.getValue().toString());
        assertThat(json.get("eventType").asString()).isEqualTo("invoice.enrichment.requested");
        assertThat(json.get("occurredAt").asString()).isEqualTo("2026-11-04T12:00:00Z");
        assertThat(json.get("correlationId").asString()).isEqualTo(invoice.id().toString());
        assertThat(json.get("payload").get("invoiceId").asString()).isEqualTo(invoice.id().toString());
        assertThat(json.get("payload").get("accessKey").asString()).isEqualTo(KEY.value());
    }

    @Test
    void repeatedKeyReturnsTheExistingInvoiceAndDoesNotTouchTheOutbox() {
        when(clock.now()).thenReturn(NOW);
        Invoice existing = Invoice.create(KEY, Instant.parse("2026-11-03T10:00:00Z"));
        when(invoices.insertIfAbsent(any())).thenReturn(false);
        when(invoices.findByAccessKey(KEY)).thenReturn(Optional.of(existing));

        CreateInvoiceResult result = service.create(KEY);

        assertThat(result.created()).isFalse();
        assertThat(result.invoice()).isSameAs(existing);
        verifyNoInteractions(outbox);
    }

    @Test
    void getByIdReturnsTheInvoice() {
        Invoice existing = Invoice.create(KEY, NOW);
        when(invoices.findById(existing.id())).thenReturn(Optional.of(existing));

        assertThat(service.getById(existing.id())).isSameAs(existing);
    }

    @Test
    void getByIdFailsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(invoices.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(InvoiceNotFoundException.class);
    }

    @Test
    void getByAccessKeyFailsWhenMissing() {
        when(invoices.findByAccessKey(KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByAccessKey(KEY)).isInstanceOf(InvoiceNotFoundException.class);
    }
}
