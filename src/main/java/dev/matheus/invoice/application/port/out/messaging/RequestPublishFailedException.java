package dev.matheus.invoice.application.port.out.messaging;

/** A solicitação não foi publicada com confirmação do broker: a linha continua no outbox e será tentada de novo. */
public class RequestPublishFailedException extends RuntimeException {

    public RequestPublishFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
