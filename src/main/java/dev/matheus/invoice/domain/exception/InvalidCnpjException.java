package dev.matheus.invoice.domain.exception;

public class InvalidCnpjException extends RuntimeException {

    public InvalidCnpjException() {
        super("CNPJ inválido");
    }
}
