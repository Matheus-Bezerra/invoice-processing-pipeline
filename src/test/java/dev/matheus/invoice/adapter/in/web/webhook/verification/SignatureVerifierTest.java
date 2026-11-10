package dev.matheus.invoice.adapter.in.web.webhook.verification;

import dev.matheus.invoice.adapter.in.web.webhook.exception.InvalidSignatureException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SignatureVerifierTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void applicationDoesNotStartWithoutTheSecret(String secret) {
        assertThatThrownBy(() -> new SignatureVerifier(secret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEBHOOK_HMAC_SECRET");
    }

    @Test
    void errorMessageNeverCarriesTheSecret() {
        SignatureVerifier verifier = new SignatureVerifier("segredo-que-nao-pode-vazar");

        assertThatThrownBy(() -> verifier.verify("sha256=" + "0".repeat(64), new byte[] {1}))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessageNotContaining("segredo-que-nao-pode-vazar")
                .hasMessageNotContaining("0".repeat(64));
    }
}
