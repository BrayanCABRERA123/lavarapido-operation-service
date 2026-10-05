package com.lavarapido.operations.infrastructure.adapter.out;

import com.lavarapido.operations.infrastructure.config.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/** Lo comun de las llamadas REST a los vecinos: tiempos de espera, token y correlation id. */
public final class RestSupport {

    private RestSupport() {
    }

    public static RestClient client(String baseUrl, Duration timeout) {
        // HttpClient del JDK: a diferencia de HttpURLConnection, soporta PATCH (avance de la reserva)
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Reenvia el token de quien llama (el vecino aplica sus propias reglas, ADR-006) y el
     * X-Correlation-Id de la peticion (cross-cutting.md §4).
     */
    public static void authorize(HttpHeaders headers) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            headers.setBearerAuth(jwt.getToken().getTokenValue());
        }
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            headers.add(CorrelationIdFilter.HEADER, correlationId);
        }
    }
}
