package dev.matheus.invoice.adapter.in.web.webhook;

/** Assinatura ausente, fora do formato ou que não confere com o corpo. A mensagem nunca traz o valor recebido. */
class InvalidSignatureException extends RuntimeException {

    InvalidSignatureException(String message) {
        super(message);
    }
}
