package com.lavarapido.operations.infrastructure.adapter.out.booking;

import com.lavarapido.operations.domain.exception.ConflictException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("BookingServiceDirectory (llamadas a booking-service)")
class BookingServiceDirectoryTest {

    private static final String KEY = "llave-de-prueba";

    private final Map<String, String> keyByRequest = new ConcurrentHashMap<>();
    // lo que responde el booking falso: estado HTTP del PATCH y estado de la reserva en el GET
    private volatile int patchStatus = 200;
    private volatile String bookingStatus = "IN_PROGRESS";
    private HttpServer server;
    private BookingServiceDirectory directory;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String request = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            keyByRequest.put(request, String.valueOf(exchange.getRequestHeaders().getFirst("X-Internal-Key")));
            int status = "PATCH".equals(exchange.getRequestMethod()) ? patchStatus : 200;
            byte[] body = (request.endsWith("/operator/bookings") ? "[]"
                    : "{\"id\":5,\"status\":\"" + bookingStatus + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        directory = new BookingServiceDirectory(base + "/api/v1", Duration.ofSeconds(2), base, KEY);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("E-01: las rutas del operario en booking llevan la llave interna (booking rechaza al operario sin ella)")
    void operatorCallsCarryTheInternalKey() {
        directory.forOperator(5L);
        directory.forOperator(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 23));
        directory.advance(5L, "IN_PROGRESS");

        assertEquals(KEY, keyByRequest.get("GET /api/v1/operator/bookings/5"));
        assertEquals(KEY, keyByRequest.get("GET /api/v1/operator/bookings"));
        assertEquals(KEY, keyByRequest.get("PATCH /api/v1/operator/bookings/5/status"));
    }

    @Test
    @DisplayName("OPS-01: 409 al avanzar, pero la reserva ya está en ese estado: el paso se da por hecho")
    void conflictButAlreadyThereCountsAsDone() {
        patchStatus = 409;
        bookingStatus = "COMPLETED";

        assertDoesNotThrow(() -> directory.advance(5L, "COMPLETED"));
        // se volvió a leer la reserva para comprobarlo
        assertEquals(KEY, keyByRequest.get("GET /api/v1/operator/bookings/5"));
    }

    @Test
    @DisplayName("OPS-01: 409 y la reserva está en otro estado (cancelada): sigue siendo un conflicto")
    void conflictInAnotherStateIsStillAConflict() {
        patchStatus = 409;
        bookingStatus = "CANCELLED";

        ConflictException error = assertThrows(ConflictException.class, () -> directory.advance(5L, "COMPLETED"));
        assertEquals("BOOKING_STATE_CONFLICT", error.code());
    }
}
