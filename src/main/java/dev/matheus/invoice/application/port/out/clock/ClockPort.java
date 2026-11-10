package dev.matheus.invoice.application.port.out.clock;

import java.time.Instant;

public interface ClockPort {

    Instant now();
}
