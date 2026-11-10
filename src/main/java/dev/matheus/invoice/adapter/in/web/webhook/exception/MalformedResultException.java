package dev.matheus.invoice.adapter.in.web.webhook.exception;

/** O corpo assinado não é um resultado bem formado (spec, seção 3.2). A mensagem nunca traz valores recebidos. */
public class MalformedResultException extends RuntimeException {

    public MalformedResultException(String message) {
        super(message);
    }
}
