package com.lavarapido.operations.infrastructure.adapter.out.identity;

import com.lavarapido.operations.domain.exception.DependencyUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SecurityUserDirectory (cuentas de los operarios en security-service)")
class SecurityUserDirectoryTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // security falso: la cuenta 1 está activa, la 2 desactivada, la 3 no existe y la 4 da error
        server.createContext("/internal/v1/users/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status;
            String body;
            if (path.endsWith("/1/contact")) {
                status = 200;
                body = "{\"firstName\":\"Oscar\",\"active\":true}";
            } else if (path.endsWith("/2/contact")) {
                status = 200;
                body = "{\"firstName\":\"Ana\",\"active\":false}";
            } else if (path.endsWith("/3/contact")) {
                status = 404;
                body = "{}";
            } else {
                status = 500;
                body = "{}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private SecurityUserDirectory directory(String key) {
        return new SecurityUserDirectory(base + "/api/v1", Duration.ofSeconds(2), base, key);
    }

    @Test
    @DisplayName("A-13: activa, desactivada o inexistente se responde; sin respuesta de security falla con 503")
    void accountCheckFailsClosed() {
        SecurityUserDirectory users = directory("llave");

        assertTrue(users.accountActive(1L));
        assertFalse(users.accountActive(2L));
        assertFalse(users.accountActive(3L));
        DependencyUnavailableException down = assertThrows(DependencyUnavailableException.class,
                () -> users.accountActive(4L));
        assertEquals("SECURITY_SERVICE_UNAVAILABLE", down.code());
        // para mostrar sigue siendo tolerante: sin respuesta, simplemente no hay contacto
        assertTrue(users.contactOf(4L).isEmpty());
    }

    @Test
    @DisplayName("A-13: sin llave interna no se puede comprobar la cuenta: 503, no se asume activa")
    void withoutTheInternalKeyTheCheckFails() {
        assertThrows(DependencyUnavailableException.class, () -> directory("").accountActive(1L));
    }
}
