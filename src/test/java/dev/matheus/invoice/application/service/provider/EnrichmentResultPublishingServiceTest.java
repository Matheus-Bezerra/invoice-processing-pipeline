package dev.matheus.invoice.application.service.provider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.matheus.invoice.application.port.out.messaging.EnrichmentResultPublisher;
import dev.matheus.invoice.application.port.out.messaging.ResultPublishFailedException;

/** Nível A: publicação do resultado recebido pelo webhook (H5 no serviço). */
@ExtendWith(MockitoExtension.class)
class EnrichmentResultPublishingServiceTest {

    private static final UUID MESSAGE_ID = UUID.randomUUID();
    private static final UUID CORRELATION_ID = UUID.randomUUID();
    private static final byte[] BODY = "{\"messageId\":\"x\"}".getBytes(StandardCharsets.UTF_8);

    @Mock
    private EnrichmentResultPublisher publisher;
    @InjectMocks
    private EnrichmentResultPublishingService service;

    @Test
    void publishesTheExactBodyWithTheIds() {
        service.publish(new PublishEnrichmentResultCommand(MESSAGE_ID, CORRELATION_ID, BODY));

        verify(publisher).publish(MESSAGE_ID, CORRELATION_ID, BODY);
    }

    @Test
    void h5_publicationFailurePropagates() {
        doThrow(new ResultPublishFailedException("sem confirmação", null)).when(publisher)
                .publish(MESSAGE_ID, CORRELATION_ID, BODY);

        assertThatThrownBy(() -> service.publish(new PublishEnrichmentResultCommand(MESSAGE_ID, CORRELATION_ID, BODY)))
                .isInstanceOf(ResultPublishFailedException.class);
    }
}
