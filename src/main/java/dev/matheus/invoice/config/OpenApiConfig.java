package dev.matheus.invoice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Título e descrição do Swagger, em português, definidos por perfil (api e webhook). */
@Configuration(proxyBeanMethods = false)
@Profile({"api", "webhook"})
class OpenApiConfig {

    @Bean
    OpenAPI openApi(@Value("${invoice.openapi.title}") String title,
                    @Value("${invoice.openapi.description}") String description) {
        return new OpenAPI().info(new Info().title(title).description(description).version("1.0"));
    }
}
