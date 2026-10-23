package com.lavarapido.operations.infrastructure.config;

import com.lavarapido.operations.domain.port.in.OperatorProfileUseCase;
import com.lavarapido.operations.domain.exception.ConflictException;
import com.lavarapido.operations.domain.exception.NotFoundException;
import com.lavarapido.operations.domain.port.in.InspectionUseCase;
import com.lavarapido.operations.domain.port.in.InspectionUseCase.InspectionView;
import com.lavarapido.operations.domain.port.in.FollowUpUseCase;
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

    @MockitoBean
    private FollowUpUseCase followUp;

    @MockitoBean
    private InspectionUseCase inspections;

    @MockitoBean
    private OperatorProfileUseCase operatorProfiles;

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
    void inspectionIsForTheAdminAndTheLinkIsPublic() throws Exception {
        given(inspections.forBooking(anyLong())).willReturn(new InspectionView(true, true, null));
        given(inspections.published("no-existe")).willThrow(new NotFoundException("INSPECTION_NOT_FOUND", "none"));

        mvc.perform(get("/api/v1/admin/inspections/50")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/inspections/50").header("Authorization", token(7L, "CLIENT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/inspections/50").header("Authorization", token(1L, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(true));
        // el enlace del cliente no pide sesion; un token que no existe es 404, no 401
        mvc.perform(get("/api/v1/public/inspections/no-existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INSPECTION_NOT_FOUND"));
    }

    @Test
    void anySignedInUserCanSeeTheFreeOperators() throws Exception {
        given(operations.availableOperators(any(), any(), anyInt())).willReturn(List.of());

        mvc.perform(get("/api/v1/operators/available").param("date", "2026-10-20").param("time", "09:00")
                        .param("minutes", "60"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/operators/available").param("date", "2026-10-20").param("time", "09:00")
                        .param("minutes", "60").header("Authorization", token(7L, "CLIENT")))
                .andExpect(status().isOk());
    }

    @Test
    void businessErrorsCarryTheirCode() throws Exception {
        given(operations.assign(anyLong(), anyInt(), anyLong(), any()))
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

    @Test
    void onlyTheAdminClosesABookingThatNobodyRegistered() throws Exception {
        given(followUp.toClose(null, null)).willReturn(List.of(new FollowUpUseCase.ToCloseView(61L,
                com.lavarapido.operations.domain.service.FollowUpPolicy.Reason.NOT_STARTED, 3)));

        mvc.perform(get("/api/v1/admin/assignments/to-close").header("Authorization", token(30L, "OPERATOR")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/assignments/to-close").header("Authorization", token(1L, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bookingId").value(61))
                .andExpect(jsonPath("$[0].reason").value("NOT_STARTED"));
        mvc.perform(post("/api/v1/admin/assignments/61/attended").header("Authorization", token(30L, "OPERATOR")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/assignments/61/attended").header("Authorization", token(1L, "ADMIN")))
                .andExpect(status().isNoContent());
        org.mockito.Mockito.verify(followUp).closeAsAttended(61L, 1L);
    }
}
