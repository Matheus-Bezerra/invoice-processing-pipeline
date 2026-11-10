package dev.matheus.invoice.adapter.in.web.webhook;

/** O corpo assinado não é um resultado bem formado (spec, seção 3.2). A mensagem nunca traz valores recebidos. */
class MalformedResultException extends RuntimeException {

    MalformedResultException(String message) {
        super(message);
    }
}
