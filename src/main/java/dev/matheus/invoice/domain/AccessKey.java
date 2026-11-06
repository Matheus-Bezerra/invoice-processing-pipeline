package dev.matheus.invoice.domain;

import dev.matheus.invoice.domain.exception.InvalidAccessKeyException;

import java.util.regex.Pattern;

/** Chave de acesso da nota: exatamente 44 dígitos, já sem espaços nas pontas. */
public record AccessKey(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[0-9]{44}$");

    public AccessKey {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new InvalidAccessKeyException();
        }
    }

    public static AccessKey of(String raw) {
        return new AccessKey(raw == null ? null : raw.trim());
    }
}
