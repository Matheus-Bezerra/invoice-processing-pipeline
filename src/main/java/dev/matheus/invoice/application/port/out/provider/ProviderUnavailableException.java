package dev.matheus.invoice.application.port.out.provider;

/** Erro técnico na chamada ao provedor ({@code 5xx}, {@code 3xx}, {@code 408}, {@code 429}, timeout ou conexão recusada): vale tentar de novo. */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
