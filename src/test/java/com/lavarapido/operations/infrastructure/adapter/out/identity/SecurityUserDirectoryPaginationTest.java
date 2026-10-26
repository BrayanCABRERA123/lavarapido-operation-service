package com.lavarapido.operations.infrastructure.adapter.out.identity;

import com.lavarapido.operations.domain.port.out.UserDirectory.UserSnapshot;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SecurityUserDirectory (lista de operarios de security-service)")
class SecurityUserDirectoryPaginationTest {

    private HttpServer server;
    private SecurityUserDirectory directory;
    private final List<String> pagesAsked = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // security falso con 101 operarios: la página 0 trae 100 y la 1 trae 1
        server.createContext("/api/v1/admin/users", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            pagesAsked.add(query);
            int page = query.contains("page=1") ? 1 : 0;
            int count = page == 0 ? 100 : 1;
            StringBuilder items = new StringBuilder();
            for (int i = 0; i < count; i++) {
                long id = page * 100L + i + 1;
                items.append(i == 0 ? "" : ",").append("{\"id\":").append(id)
                        .append(",\"email\":\"op").append(id).append("@gmail.com\",\"firstName\":\"Op\",")
                        .append("\"lastName\":\"").append(id).append("\",\"phone\":\"300\",\"active\":true}");
            }
            byte[] body = ("{\"items\":[" + items + "],\"page\":" + page + ",\"size\":100,\"totalElements\":101,"
                    + "\"totalPages\":2}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        directory = new SecurityUserDirectory(base + "/api/v1", Duration.ofSeconds(2), base, "llave");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("OPS-12: con más de 100 operarios se piden todas las páginas, no solo la primera")
    void allPagesAreRead() {
        List<UserSnapshot> operators = directory.operators();

        assertEquals(101, operators.size());
        assertEquals(2, pagesAsked.size());
        assertTrue(operators.stream().anyMatch(u -> u.id() == 101L));
    }
}
