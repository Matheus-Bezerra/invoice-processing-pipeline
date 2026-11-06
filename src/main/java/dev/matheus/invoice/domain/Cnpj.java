package dev.matheus.invoice.domain;

import dev.matheus.invoice.domain.exception.InvalidCnpjException;

import java.util.regex.Pattern;

/**
 * CNPJ alfanumérico: 12 caracteres [A-Z0-9] + 2 dígitos verificadores numéricos, sem máscara.
 * Dígitos verificadores por módulo 11, com valor de cada caractere = código ASCII - 48
 * (pesos 2 a 9 da direita para a esquerda), conforme a Receita Federal.
 */
public record Cnpj(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[A-Z0-9]{12}[0-9]{2}$");
    private static final int[] WEIGHTS = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    public Cnpj {
        if (!isValid(value)) {
            throw new InvalidCnpjException();
        }
    }

    public static Cnpj of(String raw) {
        return new Cnpj(raw);
    }

    public static boolean isValid(String raw) {
        if (raw == null || !FORMAT.matcher(raw).matches()) {
            return false;
        }
        int first = checkDigit(raw, 12);
        int second = checkDigit(raw, 13);
        return first == raw.charAt(12) - '0' && second == raw.charAt(13) - '0';
    }

    /** Calcula o dígito verificador sobre os {@code length} primeiros caracteres. */
    private static int checkDigit(String raw, int length) {
        int sum = 0;
        int offset = WEIGHTS.length - length;
        for (int i = 0; i < length; i++) {
            sum += (raw.charAt(i) - '0') * WEIGHTS[offset + i];
        }
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }
}
