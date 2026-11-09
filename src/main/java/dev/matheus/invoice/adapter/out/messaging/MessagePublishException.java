package dev.matheus.invoice.adapter.out.messaging;

/** O broker não confirmou a publicação. */
public class MessagePublishException extends RuntimeException {

    public MessagePublishException(String message) {
        super(message);
    }

    public MessagePublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
