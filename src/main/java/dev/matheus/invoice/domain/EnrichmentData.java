package dev.matheus.invoice.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Dados devolvidos pelo provedor. Todos podem faltar; {@code cancelled} é {@code Boolean}
 * (nunca {@code boolean}) para que a ausência não vire {@code false}.
 */
public record EnrichmentData(String cnpj, BigDecimal amount, LocalDate issueDate, Boolean cancelled) {
}
