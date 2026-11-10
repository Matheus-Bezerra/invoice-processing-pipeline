package dev.matheus.invoice.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CnpjTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "11222333000181",   // numérico
            "11444777000161",   // numérico
            "12ABC34501DE35"    // exemplo oficial da Receita (alfanumérico)
    })
    void acceptsValidCnpj(String raw) {
        assertThat(Cnpj.isValid(raw)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "11222333000182",    // DV errado
            "11222333000191",    // DV errado
            "12ABC34501DE36",    // DV errado (alfanumérico)
            "12abc34501de35",    // minúsculas não são normalizadas
            "12ABC34501DEAB",    // DV não numérico
            "1122233300018",     // 13 caracteres
            "112223330001811",   // 15 caracteres
            "11.222.333/0001-81",// com máscara
            "12ABC34501D-35",    // caractere inválido
            " 11222333000181"    // espaço
    })
    void rejectsInvalidCnpj(String raw) {
        assertThat(Cnpj.isValid(raw)).isFalse();
    }
}
