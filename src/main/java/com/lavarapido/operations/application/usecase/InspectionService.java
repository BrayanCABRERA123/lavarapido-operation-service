package com.lavarapido.operations.application.usecase;

import com.lavarapido.operations.domain.event.InspectionSharedEvent;
import com.lavarapido.operations.domain.exception.InvalidValueException;
import com.lavarapido.operations.domain.exception.NotFoundException;
import com.lavarapido.operations.domain.model.BookingSnapshot;
import com.lavarapido.operations.domain.model.inspection.FindingSeverity;
import com.lavarapido.operations.domain.model.inspection.InspectionFinding;
import com.lavarapido.operations.domain.model.inspection.InspectionPhase;
import com.lavarapido.operations.domain.model.inspection.InspectionPhaseStatus;
import com.lavarapido.operations.domain.model.inspection.InspectionReport;
import com.lavarapido.operations.domain.port.in.InspectionUseCase;
import com.lavarapido.operations.domain.port.out.BookingDirectory;
import com.lavarapido.operations.domain.port.out.DomainEventPublisher;
import com.lavarapido.operations.domain.port.out.InspectionRepository;
import com.lavarapido.operations.domain.port.out.InspectionRepository.StoredPhoto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Informe de inspeccion (RF-036). La reserva se lee de booking-service con el token del admin para
 * saber si el informe se puede iniciar o editar; las reglas las aplica el agregado InspectionReport.
 */
@Service
@Transactional
public class InspectionService implements InspectionUseCase {

    static final Set<String> PHOTO_TYPES = Set.of("image/jpeg", "image/png");
    static final int MAX_PHOTO_BYTES = 2 * 1024 * 1024;

    private final InspectionRepository inspections;
    private final BookingDirectory bookings;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public InspectionService(InspectionRepository inspections, BookingDirectory bookings, DomainEventPublisher events,
                             Clock clock) {
        this.inspections = inspections;
        this.bookings = bookings;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public InspectionView forBooking(long bookingId) {
        BookingSnapshot booking = requireBooking(bookingId);
        return view(booking, inspections.findByBooking(bookingId).orElse(null));
    }

    @Override
    public InspectionView start(long bookingId, long adminId) {
        BookingSnapshot booking = requireBooking(bookingId);
        InspectionReport existing = inspections.findByBooking(bookingId).orElse(null);
        if (existing != null) {
            return view(booking, existing);
        }
        InspectionReport report = InspectionReport.start(booking, newToken(), clock.instant());
        return view(booking, inspections.save(report, adminId));
    }

    @Override
    public InspectionView setPhaseStatus(long bookingId, InspectionPhase phase, InspectionPhaseStatus status,
                                         long adminId) {
        BookingSnapshot booking = requireEditableBooking(bookingId);
        InspectionReport report = requireReport(bookingId);
        report.setPhaseStatus(phase, status, clock.instant());
        return view(booking, inspections.save(report, adminId));
    }

    @Override
    public InspectionView addFinding(long bookingId, InspectionPhase phase, String area, FindingSeverity severity,
                                     String note, List<PhotoUpload> photos, long adminId) {
        List<PhotoUpload> uploads = photos == null ? List.of() : photos;
        uploads.forEach(InspectionService::requireValidPhoto);
        BookingSnapshot booking = requireEditableBooking(bookingId);
        InspectionReport report = requireReport(bookingId);
        report.addFinding(phase, area, severity, note, uploads.size(), clock.instant());
        InspectionReport saved = inspections.save(report, adminId);
        // el hallazgo nuevo es el de id mas alto: las fotos se guardan con ese id
        long findingId = saved.findings().stream().mapToLong(InspectionFinding::id).max().orElseThrow();
        uploads.forEach(photo -> inspections.addPhoto(findingId, photo.contentType(), photo.content(), adminId));
        return view(booking, requireReport(bookingId));
    }

    @Override
    public InspectionView removeFinding(long bookingId, long findingId, long adminId) {
        BookingSnapshot booking = requireEditableBooking(bookingId);
        InspectionReport report = requireReport(bookingId);
        report.removeFinding(findingId, clock.instant());
        inspections.removeFinding(findingId, adminId);
        return view(booking, inspections.save(report, adminId));
    }

    @Override
    public InspectionView publish(long bookingId, long adminId) {
        BookingSnapshot booking = requireBooking(bookingId);
        InspectionReport report = requireReport(bookingId);
        report.publish(clock.instant());
        return view(booking, inspections.save(report, adminId));
    }

    @Override
    public void share(long bookingId, long adminId) {
        BookingSnapshot booking = requireBooking(bookingId);
        InspectionReport report = requireReport(bookingId);
        report.requirePublished();
        if (booking.ownerUserId() == null) {
            throw new InvalidValueException("BOOKING_WITHOUT_CLIENT", "The booking has no client account to notify");
        }
        InspectionReport.Summary summary = report.summary();
        events.publishInspectionShared(new InspectionSharedEvent(bookingId, booking.code(), booking.ownerUserId(),
                report.booking().plate(), report.publicToken(), summary.phasesDone(), summary.phasesTotal(),
                summary.findings(), summary.majorFindings(), summary.areas(), clock.instant()));
    }

    @Override
    @Transactional(readOnly = true)
    public StoredPhoto photoForAdmin(long bookingId, long photoId) {
        InspectionReport report = requireReport(bookingId);
        return photoOf(report, photoId);
    }

    @Override
    @Transactional(readOnly = true)
    public InspectionReport published(String publicToken) {
        return inspections.findByPublicToken(publicToken)
                .filter(InspectionReport::published)
                .orElseThrow(() -> new NotFoundException("INSPECTION_NOT_FOUND", "The report does not exist"));
    }

    @Override
    @Transactional(readOnly = true)
    public StoredPhoto publishedPhoto(String publicToken, long photoId) {
        return photoOf(published(publicToken), photoId);
    }

    // ------------------------------------------------------------------ apoyo

    private InspectionView view(BookingSnapshot booking, InspectionReport report) {
        return new InspectionView(InspectionReport.availableFor(booking), InspectionReport.editableFor(booking), report);
    }

    private BookingSnapshot requireBooking(long bookingId) {
        return bookings.forAdmin(bookingId)
                .orElseThrow(() -> new NotFoundException("BOOKING_NOT_FOUND", "Booking " + bookingId + " does not exist"));
    }

    private BookingSnapshot requireEditableBooking(long bookingId) {
        BookingSnapshot booking = requireBooking(bookingId);
        InspectionReport.requireEditable(booking);
        return booking;
    }

    private InspectionReport requireReport(long bookingId) {
        return inspections.findByBooking(bookingId)
                .orElseThrow(() -> new NotFoundException("INSPECTION_NOT_FOUND", "The booking has no inspection report"));
    }

    /** Solo se sirven fotos de hallazgos vigentes de ESE informe. */
    private StoredPhoto photoOf(InspectionReport report, long photoId) {
        boolean belongs = report.findings().stream().anyMatch(f -> f.photoIds().contains(photoId));
        return inspections.photo(photoId)
                .filter(photo -> belongs && photo.reportId() == report.id())
                .orElseThrow(() -> new NotFoundException("PHOTO_NOT_FOUND", "The photo does not exist"));
    }

    private static void requireValidPhoto(PhotoUpload photo) {
        if (photo.contentType() == null || !PHOTO_TYPES.contains(photo.contentType())) {
            throw new InvalidValueException("INVALID_PHOTO", "Photos must be JPEG or PNG");
        }
        if (photo.content() == null || photo.content().length == 0 || photo.content().length > MAX_PHOTO_BYTES) {
            throw new InvalidValueException("INVALID_PHOTO", "Each photo must weigh up to 2 MB");
        }
    }

    /** 32 bytes aleatorios en base64url: imposible de adivinar a partir de la reserva. */
    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
