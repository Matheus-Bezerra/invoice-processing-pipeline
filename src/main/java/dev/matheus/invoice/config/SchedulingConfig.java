package dev.matheus.invoice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Agendamento só no worker, onde roda o relay do outbox. */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableScheduling
class SchedulingConfig {
}
