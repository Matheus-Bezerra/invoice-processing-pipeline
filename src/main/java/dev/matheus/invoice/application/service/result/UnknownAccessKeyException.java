package dev.matheus.invoice.application.service.result;

/** Resultado para uma chave sem nota cadastrada. O resultado nunca cria nota; a mensagem vai para a DLQ. */
public class UnknownAccessKeyException extends RuntimeException {

    public UnknownAccessKeyException() {
        super("Nenhuma nota cadastrada com a chave de acesso do resultado");
    }
}
