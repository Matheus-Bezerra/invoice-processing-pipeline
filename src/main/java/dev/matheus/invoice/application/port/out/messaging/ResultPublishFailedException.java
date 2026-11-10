package dev.matheus.invoice.application.port.out.messaging;

/** O resultado não foi publicado com confirmação do broker: o provedor deve reenviar o callback. */
public class ResultPublishFailedException extends RuntimeException {

    public ResultPublishFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
