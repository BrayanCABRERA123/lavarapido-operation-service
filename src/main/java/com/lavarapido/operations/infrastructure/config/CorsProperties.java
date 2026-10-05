package com.lavarapido.operations.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** De donde se permite llamar a la API. Por defecto el frontend de Angular en el puerto 4200. */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null || allowedOrigins.isEmpty()
                ? List.of("http://localhost:4200")
                : List.copyOf(allowedOrigins);
    }
}