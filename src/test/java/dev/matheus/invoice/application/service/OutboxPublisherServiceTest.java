package dev.matheus.invoice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.matheus.invoice.application.port.out.ClockPort;
import dev.matheus.invoice.application.port.out.EnrichmentRequestPublisher;
import dev.matheus.invoice.application.port.out.OutboxPort;
import dev.matheus.invoice.application.port.out.PendingRequest;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private static final OutboxRelaySettings SETTINGS = new OutboxRelaySettings(50, Duration.ofSeconds(60));

    @Mock
    private OutboxPort outbox;
    @Mock
    private EnrichmentRequestPublisher publisher;
    @Mock
    private ClockPort clock;

    private OutboxPublisherService service;

    @BeforeEach
    void setUp() {
        service = new OutboxPublisherService(outbox, publisher, clock, SETTINGS);
    }

    private static PendingRequest pending(int attempts) {
        UUID invoiceId = UUID.randomUUID();
        return new PendingRequest(UUID.randomUUID(), invoiceId, "{\"invoiceId\":\"" + invoiceId + "\"}", attempts);
    }

    @Test
    void t9_failedPublicationKeepsTheRowForTheRelayWhichPublishesItLater() {
        PendingRequest row = pending(0);
        when(clock.now()).thenReturn(NOW);
        when(outbox.lockById(row.id())).thenReturn(Optional.of(row));
        doThrow(new IllegalStateException("broker fora do ar"))
                .when(publisher).publish(row.id(), row.invoiceId(), row.payloadJson());

        assertThat(service.publish(row.id())).isFalse();

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(outbox).markFailed(eq(row.id()), eq(1), eq(NOW.plusSeconds(2)), error.capture());
        assertThat(error.getValue()).contains("broker fora do ar");
        verify(outbox, never()).delete(any());

        // O broker voltou: o relay pega a mesma linha (agora com attempts = 1) e publica o mesmo messageId.
        PendingRequest retried = new PendingRequest(row.id(), row.invoiceId(), row.payloadJson(), 1);
        when(outbox.lockDue(NOW, 50)).thenReturn(List.of(retried));
        reset(publisher); // a partir daqui a publicação funciona

        assertThat(service.publishDue()).isEqualTo(1);

        verify(publisher).publish(row.id(), row.invoiceId(), row.payloadJson());
        verify(outbox).delete(row.id());
    }

    @Test
    void publishedRowIsDeleted() {
        PendingRequest row = pending(0);
        when(outbox.lockById(row.id())).thenReturn(Optional.of(row));

        assertThat(service.publish(row.id())).isTrue();

        verify(publisher).publish(row.id(), row.invoiceId(), row.payloadJson());
        verify(outbox).delete(row.id());
        verify(outbox, never()).markFailed(any(), anyInt(), any(), any());
    }

    @Test
    void rowAlreadyReservedByAnotherPublisherIsNotPublished() {
        UUID id = UUID.randomUUID();
        when(outbox.lockById(id)).thenReturn(Optional.empty());

        assertThat(service.publish(id)).isFalse();

        verifyNoInteractions(publisher);
        verify(outbox, never()).delete(any());
    }

    @Test
    void failureOfOneRowDoesNotStopTheRestOfTheBatch() {
        PendingRequest failing = pending(2);
        PendingRequest ok = pending(0);
        when(clock.now()).thenReturn(NOW);
        when(outbox.lockDue(NOW, 50)).thenReturn(List.of(failing, ok));
        doThrow(new IllegalStateException("sem confirm"))
                .when(publisher).publish(failing.id(), failing.invoiceId(), failing.payloadJson());

        assertThat(service.publishDue()).isEqualTo(1);

        verify(outbox).markFailed(eq(failing.id()), eq(3), eq(NOW.plusSeconds(8)), any());
        verify(outbox).delete(ok.id());
        verify(outbox, never()).delete(failing.id());
    }

    @ParameterizedTest
    @CsvSource({ "1, 2", "2, 4", "3, 8", "5, 32", "6, 60", "10, 60", "64, 60", "1000, 60" })
    void backoffDoublesUpToTheCeiling(int attempts, long expectedSeconds) {
        assertThat(service.backoff(attempts)).isEqualTo(Duration.ofSeconds(expectedSeconds));
    }
}
