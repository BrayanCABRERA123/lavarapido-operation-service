package com.lavarapido.operations.infrastructure.adapter.out.identity;

import com.lavarapido.operations.domain.port.out.UserDirectory;
import com.lavarapido.operations.infrastructure.adapter.out.RestSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cuentas con rol OPERATOR en security-service (/admin/users?role=OPERATOR). Solo el admin puede
 * listarlas: con el token de un operario o de un cliente la lista llega vacia y los nombres
 * quedan en blanco, sin romper la operacion.
 */
@Component
class SecurityUserDirectory implements UserDirectory {

    private static final Logger log = LoggerFactory.getLogger(SecurityUserDirectory.class);
    private static final int PAGE_SIZE = 100;
    // tope de páginas por si security respondiera siempre lo mismo (10.000 operarios)
    private static final int MAX_PAGES = 100;

    private final RestClient client;
    // contacto interno (/internal/v1/users/{id}/contact): no pasa por el gateway y pide la llave interna
    private final RestClient internal;
    private final String internalKey;

    SecurityUserDirectory(@Value("${app.security-service.base-url}") String baseUrl,
                          @Value("${app.security-service.timeout:3s}") Duration timeout,
                          @Value("${app.security-service.internal-url:http://localhost:3001}") String internalUrl,
                          @Value("${app.security-service.internal-key:}") String internalKey) {
        this.client = RestSupport.client(baseUrl, timeout);
        this.internal = RestSupport.client(internalUrl, timeout);
        this.internalKey = internalKey == null ? "" : internalKey.strip();
    }

    @Override
    public String firstNameOf(long userId) {
        return contactOf(userId).map(ContactSnapshot::firstName).orElse("");
    }

    @Override
    public Optional<ContactSnapshot> contactOf(long userId) {
        if (internalKey.isEmpty()) {
            return Optional.empty();
        }
        try {
            ContactJson contact = internal.get()
                    .uri("/internal/v1/users/{id}/contact", userId)
                    .header("X-Internal-Key", internalKey)
                    .retrieve()
                    .body(ContactJson.class);
            return contact == null ? Optional.empty()
                    : Optional.of(new ContactSnapshot(nullToEmpty(contact.firstName()).strip(), contact.active()));
        } catch (RestClientException e) {
            log.warn("Could not read the contact of user {} from security-service: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    record ContactJson(String firstName, boolean active) {
    }

    @Override
    public boolean accountMissing(long userId) {
        if (internalKey.isEmpty()) {
            return false;
        }
        try {
            internal.get()
                    .uri("/internal/v1/users/{id}/contact", userId)
                    .header("X-Internal-Key", internalKey)
                    .retrieve()
                    .toBodilessEntity();
            return false;
        } catch (HttpClientErrorException.NotFound e) {
            return true;
        } catch (RestClientException e) {
            log.warn("Could not check the account of user {} in security-service: {}", userId, e.getMessage());
            return false;
        }
    }

    /** OPS-12: security entrega hasta 100 por página; se piden todas, no solo la primera. */
    @Override
    public List<UserSnapshot> operators() {
        List<UserSnapshot> result = new ArrayList<>();
        try {
            for (int number = 0; number < MAX_PAGES; number++) {
                int current = number;
                PageJson page = client.get()
                        .uri(uri -> uri.path("/admin/users").queryParam("role", "OPERATOR")
                                .queryParam("page", current).queryParam("size", PAGE_SIZE).build())
                        .headers(RestSupport::authorize)
                        .retrieve()
                        .body(PageJson.class);
                if (page == null || page.items() == null || page.items().isEmpty()) {
                    break;
                }
                page.items().forEach(u -> result.add(new UserSnapshot(u.id(),
                        (nullToEmpty(u.firstName()) + " " + nullToEmpty(u.lastName())).trim(),
                        nullToEmpty(u.email()), nullToEmpty(u.phone()), u.active())));
                // última página (o un security que no manda totalPages: se queda con la primera, como antes)
                if (current + 1 >= page.totalPages() || page.items().size() < PAGE_SIZE) {
                    break;
                }
            }
            return result;
        } catch (RestClientException e) {
            log.warn("Could not list operators from security-service: {}", e.getMessage());
            return List.of();
        }
    }

    record PageJson(List<UserJson> items, int totalPages) {
    }

    record UserJson(long id, String email, String firstName, String lastName, String phone, boolean active) {
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
