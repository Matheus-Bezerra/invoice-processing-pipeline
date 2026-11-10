package dev.matheus.invoice.application.service.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.matheus.invoice.application.port.out.provider.ProviderClient;
import dev.matheus.invoice.application.port.out.provider.ProviderRejectedException;
import dev.matheus.invoice.application.port.out.provider.ProviderUnavailableException;

/** Nível A: repasse da solicitação ao provedor (C1, C2 e C3 no serviço). */
@ExtendWith(MockitoExtension.class)
class EnrichmentRequestForwardingServiceTest {

    private static final UUID MESSAGE_ID = UUID.randomUUID();
    private static final UUID CORRELATION_ID = UUID.randomUUID();
    private static final String BODY = "{\"messageId\":\"" + MESSAGE_ID + "\"}";

    @Mock
    private ProviderClient provider;
    @InjectMocks
    private EnrichmentRequestForwardingService service;

    @Test
    void c1_forwardsTheExactBodyAndCorrelationIdToTheProvider() {
        service.forward(new ForwardEnrichmentRequestCommand(MESSAGE_ID, CORRELATION_ID, BODY));

        verify(provider).requestEnrichment(CORRELATION_ID, BODY);
    }

    @Test
    void c2_providerRejectionPropagates() {
        doThrow(new ProviderRejectedException(400)).when(provider).requestEnrichment(CORRELATION_ID, BODY);

        assertThatThrownBy(() -> service.forward(new ForwardEnrichmentRequestCommand(MESSAGE_ID, CORRELATION_ID, BODY)))
                .isInstanceOfSatisfying(ProviderRejectedException.class,
                        e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void c3_providerUnavailabilityPropagates() {
        doThrow(new ProviderUnavailableException("fora do ar", null)).when(provider)
                .requestEnrichment(CORRELATION_ID, BODY);

        assertThatThrownBy(() -> service.forward(new ForwardEnrichmentRequestCommand(MESSAGE_ID, CORRELATION_ID, BODY)))
                .isInstanceOf(ProviderUnavailableException.class);
    }
}
