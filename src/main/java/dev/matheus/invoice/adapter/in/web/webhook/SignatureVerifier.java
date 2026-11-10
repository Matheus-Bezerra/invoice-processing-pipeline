package dev.matheus.invoice.adapter.in.web.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Confere o {@code X-Signature: sha256=<hex>}: HMAC-SHA256 dos bytes exatos do corpo com o segredo compartilhado,
 * comparado em tempo constante. Nada do segredo nem da assinatura vai para mensagens de erro ou logs.
 */
@Component
@Profile("webhook")
class SignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";
    private static final int HEX_LENGTH = 64;

    private final SecretKeySpec key;

    SignatureVerifier(@Value("${invoice.webhook.hmac-secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("A variável WEBHOOK_HMAC_SECRET é obrigatória no perfil webhook");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    void verify(String header, byte[] body) {
        if (header == null || !header.startsWith(PREFIX)) {
            throw new InvalidSignatureException("Cabeçalho X-Signature ausente ou fora do formato sha256=<hex>");
        }
        String hex = header.substring(PREFIX.length());
        byte[] provided;
        try {
            if (hex.length() != HEX_LENGTH) {
                throw new IllegalArgumentException();
            }
            provided = HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            throw new InvalidSignatureException("Cabeçalho X-Signature fora do formato sha256=<hex>");
        }
        if (!MessageDigest.isEqual(provided, hmac(body))) {
            throw new InvalidSignatureException("A assinatura não confere com o corpo recebido");
        }
    }

    private byte[] hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponível", e);
        }
    }
}
