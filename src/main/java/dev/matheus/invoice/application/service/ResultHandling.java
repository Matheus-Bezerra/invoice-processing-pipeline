package dev.matheus.invoice.application.service;

/** O que aconteceu com uma mensagem de resultado. Em todos os casos a mensagem recebe {@code ack}. */
public enum ResultHandling {
    APPROVED,
    REJECTED,
    DUPLICATE,
    IGNORED_ALREADY_FINAL
}
