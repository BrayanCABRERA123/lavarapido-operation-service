package com.lavarapido.operations;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

// punto de entrada del operations-service
@SpringBootApplication
public class OperationsServiceApplication {

    public static void main(String[] args) {
        // El JVM corre en UTC (igual que booking): con hibernate.jdbc.time_zone=UTC las columnas
        // TIME de la disponibilidad no se corren. La hora del lavadero se aplica con app.zone.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(OperationsServiceApplication.class, args);
    }
}
