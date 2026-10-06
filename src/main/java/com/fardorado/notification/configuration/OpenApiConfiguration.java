package com.fardorado.notification.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API metadata for the generated OpenAPI document. Endpoint documentation
 * itself lives on the controller interfaces.
 */
@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI notificationServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Notification Service API")
                .version("v1")
                .description("""
                        Inspect and replay notification events. Every operation is scoped to the \
                        calling client, identified by the X-Client-Id request header."""));
    }
}
