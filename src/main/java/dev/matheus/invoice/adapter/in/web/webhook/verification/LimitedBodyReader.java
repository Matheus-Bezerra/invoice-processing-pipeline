package dev.matheus.invoice.adapter.in.web.webhook.verification;

import dev.matheus.invoice.adapter.in.web.webhook.exception.PayloadTooLargeException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Lê o corpo do callback com teto de tamanho, antes da verificação HMAC. Lê no máximo {@code limite + 1} bytes,
 * então um corpo enorme (com {@code Content-Length} ou em <i>chunked</i>) nunca é carregado inteiro na memória.
 */
@Component
@Profile("webhook")
public class LimitedBodyReader {

    private final int maxBytes;

    public LimitedBodyReader(@Value("${invoice.webhook.max-body-bytes}") int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalStateException("invoice.webhook.max-body-bytes precisa ser maior que zero");
        }
        this.maxBytes = maxBytes;
    }

    public byte[] read(InputStream body) {
        if (body == null) {
            return new byte[0];
        }
        try {
            byte[] raw = body.readNBytes(maxBytes + 1);
            if (raw.length > maxBytes) {
                throw new PayloadTooLargeException(
                        "O corpo do callback passa do limite de %d bytes".formatted(maxBytes));
            }
            return raw;
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o corpo do callback", e);
        }
    }
}
