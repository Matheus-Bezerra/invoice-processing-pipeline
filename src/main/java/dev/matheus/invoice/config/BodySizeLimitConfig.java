package dev.matheus.invoice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import dev.matheus.invoice.adapter.in.web.filter.BodySizeLimitFilter;

/** Teto do corpo das requisições: um filtro por perfil web, com o limite de cada um (spec, seções 2.1 e 2.5). */
@Configuration(proxyBeanMethods = false)
public class BodySizeLimitConfig {

    @Bean
    @Profile("api")
    BodySizeLimitFilter apiBodySizeLimitFilter(@Value("${invoice.api.max-body-bytes}") int maxBytes) {
        return new BodySizeLimitFilter(maxBytes);
    }

    @Bean
    @Profile("webhook")
    BodySizeLimitFilter webhookBodySizeLimitFilter(@Value("${invoice.webhook.max-body-bytes}") int maxBytes) {
        return new BodySizeLimitFilter(maxBytes);
    }
}
