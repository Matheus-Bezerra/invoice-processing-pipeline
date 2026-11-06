package dev.matheus.invoice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.matheus.invoice.domain.exception.InvalidAccessKeyException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import org.junit.jupiter.api.Test;

class AccessKeyTest {

    private static final String VALID = "35260112345678000199550010000012341000012345";

    @Test
    void acceptsExactly44Digits() {
        assertThat(AccessKey.of(VALID).value()).isEqualTo(VALID);
    }

    @Test
    void trimsSurroundingSpaces() {
        assertThat(AccessKey.of("  " + VALID + " \n").value()).isEqualTo(VALID);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "3526011234567800019955001000001234100001234",
            "352601123456780001995500100000123410000123456",
            "3526011234567800019955001000001234100001234A",
            "3526.0112.3456.7800.0199.5500.1000.0012.3410.0001.2345",
            "35-26011234567800019955001000001234100001234",
            "3526011234567800019955001000001234100001234é",
            "35260112345678 000199550010000012341000012345"
    })
    void rejectsInvalidKeys(String raw) {
        assertThatThrownBy(() -> AccessKey.of(raw))
                .isInstanceOf(InvalidAccessKeyException.class)
                .hasMessage("A chave de acesso deve conter exatamente 44 dígitos");
    }
}
