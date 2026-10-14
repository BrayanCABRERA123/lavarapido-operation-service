package com.lavarapido.operations.infrastructure.adapter.in.web;

import com.lavarapido.operations.domain.exception.InvalidValueException;
import com.lavarapido.operations.domain.model.inspection.FindingSeverity;
import com.lavarapido.operations.domain.model.inspection.InspectionPhase;
import com.lavarapido.operations.domain.port.in.InspectionUseCase;
import com.lavarapido.operations.domain.port.in.InspectionUseCase.PhotoUpload;
import com.lavarapido.operations.domain.port.out.InspectionRepository.StoredPhoto;
import com.lavarapido.operations.infrastructure.adapter.in.web.dto.InspectionDtos.InspectionResponse;
import com.lavarapido.operations.infrastructure.adapter.in.web.dto.InspectionDtos.PhaseStatusRequest;
import com.lavarapido.operations.infrastructure.adapter.in.web.dto.InspectionDtos.PublicReportResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Informe de inspeccion del vehiculo (RF-027): el admin lo llena y el cliente lo ve por enlace. */
final class InspectionControllers {

    private InspectionControllers() {
    }

    private static ResponseEntity<byte[]> image(StoredPhoto photo) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.contentType()))
                // la foto no cambia nunca: el navegador la guarda una hora
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .body(photo.content());
    }

    /** ADMIN: /api/v1/admin/inspections/{bookingId}/... */
    @RestController
    @RequestMapping("/api/v1/admin/inspections/{bookingId}")
    static class AdminInspectionController {

        private final InspectionUseCase inspections;

        AdminInspectionController(InspectionUseCase inspections) {
            this.inspections = inspections;
        }

        @GetMapping
        InspectionResponse get(@PathVariable long bookingId) {
            return InspectionResponse.from(inspections.forBooking(bookingId));
        }

        @PostMapping
        InspectionResponse start(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId) {
            return InspectionResponse.from(inspections.start(bookingId, AuthenticatedUser.userId(jwt)));
        }

        @PatchMapping("/phases/{phase}")
        InspectionResponse phase(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId,
                                 @PathVariable InspectionPhase phase, @Valid @RequestBody PhaseStatusRequest request) {
            return InspectionResponse.from(inspections.setPhaseStatus(bookingId, phase, request.status(),
                    AuthenticatedUser.userId(jwt)));
        }

        /** multipart: phase, area, severity, note y hasta 5 fotos (photos). */
        @PostMapping(value = "/findings", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        @ResponseStatus(HttpStatus.CREATED)
        InspectionResponse addFinding(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId,
                                      @RequestParam InspectionPhase phase, @RequestParam String area,
                                      @RequestParam FindingSeverity severity,
                                      @RequestParam(required = false) String note,
                                      @RequestParam(required = false) List<MultipartFile> photos) {
            List<PhotoUpload> uploads = new ArrayList<>();
            for (MultipartFile file : photos == null ? List.<MultipartFile>of() : photos) {
                try {
                    uploads.add(new PhotoUpload(file.getContentType(), file.getBytes()));
                } catch (IOException e) {
                    throw new InvalidValueException("INVALID_PHOTO", "The photo could not be read");
                }
            }
            return InspectionResponse.from(inspections.addFinding(bookingId, phase, area, severity, note, uploads,
                    AuthenticatedUser.userId(jwt)));
        }

        @DeleteMapping("/findings/{findingId}")
        InspectionResponse removeFinding(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId,
                                         @PathVariable long findingId) {
            return InspectionResponse.from(inspections.removeFinding(bookingId, findingId, AuthenticatedUser.userId(jwt)));
        }

        @PostMapping("/publish")
        InspectionResponse publish(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId) {
            return InspectionResponse.from(inspections.publish(bookingId, AuthenticatedUser.userId(jwt)));
        }

        /** Avisa al cliente (bandeja, push y correo) con el resumen y el enlace publico. */
        @PostMapping("/share")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void share(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId) {
            inspections.share(bookingId, AuthenticatedUser.userId(jwt));
        }

        @GetMapping("/photos/{photoId}")
        ResponseEntity<byte[]> photo(@PathVariable long bookingId, @PathVariable long photoId) {
            return image(inspections.photoForAdmin(bookingId, photoId));
        }
    }

    /** Publico, sin sesion y solo lectura: /api/v1/public/inspections/{token} (solo informes publicados). */
    @RestController
    @RequestMapping("/api/v1/public/inspections/{token}")
    static class PublicInspectionController {

        private final InspectionUseCase inspections;

        PublicInspectionController(InspectionUseCase inspections) {
            this.inspections = inspections;
        }

        @GetMapping
        PublicReportResponse get(@PathVariable String token) {
            return PublicReportResponse.from(inspections.published(token));
        }

        @GetMapping("/photos/{photoId}")
        ResponseEntity<byte[]> photo(@PathVariable String token, @PathVariable long photoId) {
            return image(inspections.publishedPhoto(token, photoId));
        }
    }
}
