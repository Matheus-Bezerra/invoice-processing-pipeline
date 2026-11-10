package dev.matheus.invoice.shared;

import java.util.Objects;
import java.util.stream.Collectors;

import tools.jackson.core.JacksonException;

/**
 * Descreve uma falha de leitura de JSON só pelo caminho do campo. A mensagem do Jackson traz o valor recebido, que não
 * pode ir para o log nem para a resposta. Usado pelos dois consumers e pelo parser do webhook.
 */
public final class JsonReadFailure {

    private JsonReadFailure() {
    }

    public static String describe(JacksonException e) {
        String path = e.getPath().stream()
                .map(JacksonException.Reference::getPropertyName)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("."));
        return path.isEmpty() ? "JSON inválido" : "campo com tipo ou formato ilegível: " + path;
    }
}
