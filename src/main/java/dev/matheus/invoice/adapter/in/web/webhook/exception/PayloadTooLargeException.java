package dev.matheus.invoice.adapter.in.web.webhook.exception;

/** O corpo do callback passa do limite configurado. Verificado antes da assinatura, para não ler o corpo inteiro. */
public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(String message) {
        super(message);
    }
}
