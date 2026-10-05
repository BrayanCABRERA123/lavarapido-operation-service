package com.lavarapido.operations.infrastructure.config;

import com.lavarapido.operations.domain.exception.ConflictException;
import com.lavarapido.operations.domain.port.in.OperationsUseCase;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrato HTTP y reglas de seguridad por rol, con los casos de uso simulados. */
@WebMvcTest(properties = {
        "security.jwt.secret=" + OperationsApiWebTest.SECRET,
        "security.jwt.issuer=" + OperationsApiWebTest.ISSUER,
        "security.jwt.audience=" + OperationsApiWebTest.AUDIENCE,
        "security.jwt.access-token-ttl=1h"
})
@Import({SecurityConfig.class, JwtConfig.class, ProblemDetailsSecurityHandler.class, CorrelationIdFilter.class})
class OperationsApiWebTest {

    static final String SECRET = "test-secret-with-at-least-thirty-two-bytes!!";
    static final String ISSUER = "lavarapido-security-service";
    static final String AUDIENCE = "lavarapido-api";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OperationsUseCase operations;

    private static String token(long userId, String role) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("roles", List.of(role))
                .build();
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    @Test
    void everythingNeedsAToken() throws Exception {
        mvc.perform(get("/api/v1/admin/operators")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/operator/services")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isNotFound()); // publico, pero sin actuator en el slice
    }

    @Test
    void eachAreaIsForItsRole() throws Exception {
        given(operations.operators()).willReturn(List.of());
        given(operations.myServices(any(), any(), anyLong())).willReturn(List.of());
        given(operations.myGivenRatings(anyLong())).willReturn(List.of());

        mvc.perform(get("/api/v1/admin/operators").header("Authorization", token(1L, "ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/operators").header("Authorization", token(30L, "OPERATOR")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/operator/services").header("Authorization", token(30L, "OPERATOR")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/operator/services").header("Authorization", token(7L, "CLIENT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ratings/me").header("Authorization", token(7L, "CLIENT"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/ratings/me").header("Authorization", token(30L, "OPERATOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void businessErrorsCarryTheirCode() throws Exception {
        given(operations.assign(anyLong(), anyInt(), anyLong()))
                .willThrow(new ConflictException("OPERATOR_BUSY", "busy"));

        mvc.perform(put("/api/v1/admin/assignments/50").header("Authorization", token(1L, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"operatorId\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPERATOR_BUSY"));
    }

    @Test
    void ratingIsValidated() throws Exception {
        mvc.perform(post("/api/v1/ratings/50").header("Authorization", token(7L, "CLIENT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
