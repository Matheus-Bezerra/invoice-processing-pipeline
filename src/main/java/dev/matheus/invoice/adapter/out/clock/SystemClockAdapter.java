package dev.matheus.invoice.adapter.out.clock;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Component;

import dev.matheus.invoice.application.port.out.clock.ClockPort;

@Component
public class SystemClockAdapter implements ClockPort {

    @Override
    public Instant now() {
        // O PostgreSQL guarda microssegundos; truncar evita diferença entre o valor gravado e o lido.
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
