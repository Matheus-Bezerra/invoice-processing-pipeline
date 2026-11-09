package dev.matheus.invoice.application.service;

import java.time.Duration;

/** Parâmetros do relay do outbox (spec, seções 4.7 e 8). */
public record OutboxRelaySettings(int batchSize, Duration maxBackoff) {
}
