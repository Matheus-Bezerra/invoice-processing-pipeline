package dev.matheus.invoice.domain.exception;

public class InvalidAccessKeyException extends RuntimeException {

    public InvalidAccessKeyException() {
        super("A chave de acesso deve conter exatamente 44 dígitos");
    }
}
