package com.lavarapido.operations.infrastructure.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lavarapido.operations.domain.model.inspection.InspectionFinding;
import com.lavarapido.operations.domain.model.inspection.InspectionPhaseStatus;
import com.lavarapido.operations.domain.model.inspection.InspectionReport;
import com.lavarapido.operations.domain.port.in.InspectionUseCase.InspectionView;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.function.LongFunction;

/**
 * Contratos HTTP del informe de inspeccion. Cada foto lleva la url desde la que se pide (relativa a
 * /api/v1): la del admin exige sesion y la publica solo funciona con el informe publicado.
 */
public final class InspectionDtos {

    private InspectionDtos() {
    }

    public record PhaseStatusRequest(@NotNull InspectionPhaseStatus status) {
    }

    public record PhotoDto(long id, String url) {
    }

    public record FindingDto(long id, String phase, String area, String severity, String note, Instant createdAt,
                             List<PhotoDto> photos) {

        static FindingDto from(InspectionFinding finding, LongFunction<String> photoUrl) {
            return new FindingDto(finding.id(), finding.phase().name(), finding.area(), finding.severity().name(),
                    finding.note(), finding.createdAt(),
                    finding.photoIds().stream().map(id -> new PhotoDto(id, photoUrl.apply(id))).toList());
        }
    }

    public record PhaseDto(String phase, String status, Instant startedAt, Instant finishedAt,
                           List<FindingDto> findings) {
    }

    public record SummaryDto(int phasesDone, int phasesTotal, int findings, int majorFindings, int progressPercent) {

        static SummaryDto from(InspectionReport.Summary s) {
            int progress = s.phasesTotal() == 0 ? 0 : Math.round(s.phasesDone() * 100f / s.phasesTotal());
            return new SummaryDto(s.phasesDone(), s.phasesTotal(), s.findings(), s.majorFindings(), progress);
        }
    }

    public record BookingDto(String code, String vehicle, String plate, String services, LocalDate date,
                             @JsonFormat(pattern = "HH:mm") LocalTime time, int durationMinutes) {
    }

    public record ReportDto(long bookingId, BookingDto booking, String publicToken, List<PhaseDto> phases,
                            SummaryDto summary, boolean published, Instant publishedAt, Instant updatedAt) {
    }

    /** Lo que ve el admin: si la reserva admite informe, si se puede editar y el informe (o null). */
    public record InspectionResponse(boolean available, boolean editable, ReportDto report) {

        public static InspectionResponse from(InspectionView view) {
            InspectionReport r = view.report();
            if (r == null) {
                return new InspectionResponse(view.available(), view.editable(), null);
            }
            String base = "/admin/inspections/" + r.bookingId() + "/photos/";
            InspectionReport.BookingInfo b = r.booking();
            ReportDto report = new ReportDto(r.bookingId(),
                    new BookingDto(b.code(), b.vehicle(), b.plate(), b.services(), b.date(), b.time(), b.durationMinutes()),
                    r.publicToken(), phasesOf(r, id -> base + id), SummaryDto.from(r.summary()), r.published(),
                    r.publishedAt(), r.updatedAt());
            return new InspectionResponse(view.available(), view.editable(), report);
        }
    }

    /**
     * Lo que ve el cliente por el enlace: sin ids de la reserva ni token, y la placa enmascarada por
     * si el enlace termina en manos de otra persona.
     */
    public record PublicReportResponse(String bookingCode, String vehicle, String maskedPlate, String services,
                                       LocalDate date, @JsonFormat(pattern = "HH:mm") LocalTime time,
                                       List<PhaseDto> phases, SummaryDto summary, Instant publishedAt,
                                       Instant updatedAt) {

        public static PublicReportResponse from(InspectionReport r) {
            String base = "/public/inspections/" + r.publicToken() + "/photos/";
            InspectionReport.BookingInfo b = r.booking();
            return new PublicReportResponse(b.code(), b.vehicle(), r.maskedPlate(), b.services(), b.date(), b.time(),
                    phasesOf(r, id -> base + id), SummaryDto.from(r.summary()), r.publishedAt(), r.updatedAt());
        }
    }

    private static List<PhaseDto> phasesOf(InspectionReport report, LongFunction<String> photoUrl) {
        return report.phases().stream()
                .map(p -> new PhaseDto(p.phase().name(), p.status().name(), p.startedAt(), p.finishedAt(),
                        report.findingsOf(p.phase()).stream().map(f -> FindingDto.from(f, photoUrl)).toList()))
                .toList();
    }
}
