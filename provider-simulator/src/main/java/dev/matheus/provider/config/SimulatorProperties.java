package dev.matheus.provider.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuração do simulador ({@code simulator.*} no {@code application.yml}). */
@ConfigurationProperties("simulator")
public record SimulatorProperties(String webhookUrl, String hmacSecret, Duration delay, Callback callback) {

    public record Callback(Duration connectTimeout, Duration readTimeout, List<Duration> retryWaits) {
    }
}
