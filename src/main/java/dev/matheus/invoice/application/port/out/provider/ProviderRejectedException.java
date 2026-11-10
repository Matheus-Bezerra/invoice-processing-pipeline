package dev.matheus.invoice.application.port.out.provider;

/** O provedor respondeu {@code 4xx}: tentar de novo não resolveria. */
public class ProviderRejectedException extends RuntimeException {

    private final int status;

    public ProviderRejectedException(int status) {
        super("Provedor recusou a solicitação (status " + status + ")");
        this.status = status;
    }

    public int status() {
        return status;
    }
}
