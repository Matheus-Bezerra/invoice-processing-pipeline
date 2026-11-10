package dev.matheus.invoice.adapter.in.web.webhook.exception;

/** Assinatura ausente, fora do formato ou que não confere com o corpo. A mensagem nunca traz o valor recebido. */
public class InvalidSignatureException extends RuntimeException {

    public InvalidSignatureException(String message) {
        super(message);
    }
}
