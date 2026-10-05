package com.lavarapido.operations.infrastructure.adapter.out.identity;

import com.lavarapido.operations.domain.port.out.UserDirectory;
import com.lavarapido.operations.infrastructure.adapter.out.RestSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;

/**
 * Cuentas con rol OPERATOR en security-service (/admin/users?role=OPERATOR). Solo el admin puede
 * listarlas: con el token de un operario o de un cliente la lista llega vacia y los nombres
 * quedan en blanco, sin romper la operacion.
 */
@Component
class SecurityUserDirectory implements UserDirectory {

    private static final Logger log = LoggerFactory.getLogger(SecurityUserDirectory.class);
    private static final int PAGE_SIZE = 100;

    private final RestClient client;

    SecurityUserDirectory(@Value("${app.security-service.base-url}") String baseUrl,
                          @Value("${app.security-service.timeout:3s}") Duration timeout) {
        this.client = RestSupport.client(baseUrl, timeout);
    }

    @Override
    public List<UserSnapshot> operators() {
        try {
            PageJson page = client.get()
                    .uri(uri -> uri.path("/admin/users").queryParam("role", "OPERATOR")
                            .queryParam("page", 0).queryParam("size", PAGE_SIZE).build())
                    .headers(RestSupport::authorize)
                    .retrieve()
                    .body(PageJson.class);
            if (page == null || page.items() == null) {
                return List.of();
            }
            return page.items().stream()
                    .map(u -> new UserSnapshot(u.id(), (nullToEmpty(u.firstName()) + " " + nullToEmpty(u.lastName())).trim(),
                            nullToEmpty(u.email()), nullToEmpty(u.phone()), u.active()))
                    .toList();
        } catch (RestClientException e) {
            log.warn("Could not list operators from security-service: {}", e.getMessage());
            return List.of();
        }
    }

    record PageJson(List<UserJson> items) {
    }

    record UserJson(long id, String email, String firstName, String lastName, String phone, boolean active) {
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
