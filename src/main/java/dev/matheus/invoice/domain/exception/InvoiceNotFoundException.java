package dev.matheus.invoice.domain.exception;

public class InvoiceNotFoundException extends RuntimeException {

    public InvoiceNotFoundException() {
        super("Nenhuma nota encontrada para o identificador informado");
    }
}
