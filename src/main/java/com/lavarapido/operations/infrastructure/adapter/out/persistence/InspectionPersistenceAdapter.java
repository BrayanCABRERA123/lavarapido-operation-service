package com.lavarapido.operations.infrastructure.adapter.out.persistence;

import com.lavarapido.operations.domain.model.inspection.FindingSeverity;
import com.lavarapido.operations.domain.model.inspection.InspectionFinding;
import com.lavarapido.operations.domain.model.inspection.InspectionPhase;
import com.lavarapido.operations.domain.model.inspection.InspectionPhaseStatus;
import com.lavarapido.operations.domain.model.inspection.InspectionReport;
import com.lavarapido.operations.domain.port.out.InspectionRepository;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.InspectionFindingJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.InspectionPhaseJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.InspectionPhotoJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.InspectionReportJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.repository.InspectionJpaRepositories.InspectionFindingJpaRepository;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.repository.InspectionJpaRepositories.InspectionPhaseJpaRepository;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.repository.InspectionJpaRepositories.InspectionPhotoJpaRepository;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.repository.InspectionJpaRepositories.InspectionReportJpaRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Arma y guarda el agregado InspectionReport en las 4 tablas de la migracion 033. */
@Component
class InspectionPersistenceAdapter implements InspectionRepository {

    private final InspectionReportJpaRepository reports;
    private final InspectionPhaseJpaRepository phases;
    private final InspectionFindingJpaRepository findings;
    private final InspectionPhotoJpaRepository photos;
    private final Clock clock;

    InspectionPersistenceAdapter(InspectionReportJpaRepository reports, InspectionPhaseJpaRepository phases,
                                 InspectionFindingJpaRepository findings, InspectionPhotoJpaRepository photos,
                                 Clock clock) {
        this.reports = reports;
        this.phases = phases;
        this.findings = findings;
        this.photos = photos;
        this.clock = clock;
    }

    @Override
    public Optional<InspectionReport> findByBooking(long bookingId) {
        return reports.findByBookingId(bookingId).map(this::toDomain);
    }

    @Override
    public Optional<InspectionReport> findByPublicToken(String publicToken) {
        return reports.findByPublicToken(publicToken).map(this::toDomain);
    }

    @Override
    public InspectionReport save(InspectionReport report, long actor) {
        InspectionReportJpaEntity entity = report.id() == null ? newReport(report, actor)
                : reports.findById(report.id()).orElseThrow();
        entity.setPublished(report.published());
        entity.setPublishedAt(report.publishedAt());
        entity.setUpdatedBy(actor);
        entity = reports.saveAndFlush(entity);
        long reportId = entity.getId();

        Map<String, InspectionPhaseJpaEntity> saved = phases.findByReportId(reportId).stream()
                .collect(Collectors.toMap(InspectionPhaseJpaEntity::getPhase, Function.identity()));
        for (InspectionReport.PhaseState state : report.phases()) {
            InspectionPhaseJpaEntity phase = saved.get(state.phase().name());
            if (phase == null) {
                phase = new InspectionPhaseJpaEntity();
                phase.setReportId(reportId);
                phase.setPhase(state.phase().name());
            }
            phase.setStatus(state.status().name());
            phase.setStartedAt(state.startedAt());
            phase.setFinishedAt(state.finishedAt());
            phases.save(phase);
        }

        for (InspectionFinding finding : report.findings()) {
            if (finding.id() != null) {
                continue;
            }
            InspectionFindingJpaEntity row = new InspectionFindingJpaEntity();
            row.setReportId(reportId);
            row.setPhase(finding.phase().name());
            row.setArea(finding.area());
            row.setSeverity(finding.severity().name());
            row.setNote(finding.note().isEmpty() ? null : finding.note());
            row.setCreatedAt(finding.createdAt());
            row.setCreatedBy(actor);
            findings.save(row);
        }
        // toca la fila aunque solo cambien fases o hallazgos: el trigger mueve updated_at
        reports.touch(reportId, actor);
        return reports.findById(reportId).map(this::toDomain).orElseThrow();
    }

    @Override
    public void removeFinding(long findingId, long actor) {
        findings.findById(findingId).ifPresent(row -> {
            row.setDeletedAt(clock.instant());
            row.setDeletedBy(actor);
            findings.save(row);
        });
    }

    @Override
    public long addPhoto(long findingId, String contentType, byte[] content, long actor) {
        InspectionPhotoJpaEntity photo = new InspectionPhotoJpaEntity();
        photo.setFindingId(findingId);
        photo.setContentType(contentType);
        photo.setContent(content);
        photo.setSizeBytes(content.length);
        photo.setCreatedBy(actor);
        return photos.save(photo).getId();
    }

    @Override
    public Optional<StoredPhoto> photo(long photoId) {
        return photos.withReport(photoId)
                .map(p -> new StoredPhoto(p.getId(), p.getReportId(), p.getContentType(), p.getContent()));
    }

    // ------------------------------------------------------------------ mapeo

    private static InspectionReportJpaEntity newReport(InspectionReport report, long actor) {
        InspectionReportJpaEntity entity = new InspectionReportJpaEntity();
        InspectionReport.BookingInfo booking = report.booking();
        entity.setBookingId(report.bookingId());
        entity.setBookingCode(booking.code());
        entity.setVehicle(booking.vehicle());
        entity.setPlate(booking.plate());
        entity.setServices(booking.services());
        entity.setBookingDate(booking.date());
        entity.setBookingTime(booking.time());
        entity.setDurationMinutes(booking.durationMinutes());
        entity.setPublicToken(report.publicToken());
        entity.setCreatedBy(actor);
        return entity;
    }

    private InspectionReport toDomain(InspectionReportJpaEntity entity) {
        long reportId = entity.getId();
        List<InspectionReport.PhaseState> phaseStates = phases.findByReportId(reportId).stream()
                .map(p -> new InspectionReport.PhaseState(InspectionPhase.valueOf(p.getPhase()),
                        InspectionPhaseStatus.valueOf(p.getStatus()), p.getStartedAt(), p.getFinishedAt()))
                .toList();
        List<InspectionFindingJpaEntity> rows = findings.findByReportIdAndDeletedAtIsNullOrderByIdAsc(reportId);
        Set<Long> findingIds = rows.stream().map(InspectionFindingJpaEntity::getId).collect(Collectors.toSet());
        Map<Long, List<Long>> photoIds = findingIds.isEmpty() ? Map.of()
                : photos.refsOf(findingIds).stream().collect(Collectors.groupingBy(
                        ref -> ref.getFindingId(), Collectors.mapping(ref -> ref.getId(), Collectors.toList())));
        List<InspectionFinding> domainFindings = new ArrayList<>();
        for (InspectionFindingJpaEntity row : rows) {
            domainFindings.add(new InspectionFinding(row.getId(), InspectionPhase.valueOf(row.getPhase()), row.getArea(),
                    FindingSeverity.valueOf(row.getSeverity()), row.getNote() == null ? "" : row.getNote(),
                    photoIds.getOrDefault(row.getId(), List.of()), row.getCreatedAt()));
        }
        InspectionReport.BookingInfo booking = new InspectionReport.BookingInfo(entity.getBookingCode(),
                entity.getVehicle(), entity.getPlate(), entity.getServices(), entity.getBookingDate(),
                entity.getBookingTime(), entity.getDurationMinutes());
        return InspectionReport.restore(reportId, entity.getBookingId(), booking, entity.getPublicToken(), phaseStates,
                domainFindings, Boolean.TRUE.equals(entity.getPublished()), entity.getPublishedAt(),
                entity.getUpdatedAt() == null ? entity.getCreatedAt() : entity.getUpdatedAt());
    }
}
