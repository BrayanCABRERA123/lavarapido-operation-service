package com.lavarapido.operations.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** El reloj y la zona del lavadero entran por el contexto para que las pruebas los fijen. */
@Configuration
class DomainConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Hora del lavadero: las fechas de turnos, ausencias y reservas se leen en esta zona. */
    @Bean
    ZoneId shopZone(@Value("${app.zone:America/Bogota}") String zone) {
        return ZoneId.of(zone);
    }
}
