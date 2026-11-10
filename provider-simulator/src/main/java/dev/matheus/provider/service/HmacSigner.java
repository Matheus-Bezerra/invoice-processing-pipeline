package dev.matheus.provider.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import dev.matheus.provider.config.SimulatorProperties;

/**
 * Assina o corpo do callback: {@code sha256=<hex>} com HMAC-SHA256 dos bytes exatos (spec, seção 2.5), o mesmo
 * algoritmo que o {@code invoice-webhook} confere. O segredo nunca vai para logs nem mensagens de erro.
 */
@Component
public class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public HmacSigner(SimulatorProperties properties) {
        String secret = properties.hmacSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("A variável WEBHOOK_HMAC_SECRET é obrigatória no simulador");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Não foi possível calcular o HMAC do callback", e);
        }
    }
}
