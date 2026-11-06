package dev.matheus.invoice.domain;

/** Resultado de aplicar um enriquecimento a uma nota. */
public enum ProcessingOutcome {
    APPROVED,
    REJECTED,
    IGNORED_ALREADY_FINAL
}
