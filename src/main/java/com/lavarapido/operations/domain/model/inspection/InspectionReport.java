package com.lavarapido.operations.domain.model.inspection;

import com.lavarapido.operations.domain.exception.ConflictException;
import com.lavarapido.operations.domain.exception.InvalidValueException;
import com.lavarapido.operations.domain.exception.NotFoundException;
import com.lavarapido.operations.domain.model.BookingSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Informe de inspeccion del vehiculo en servicios largos (RF-036). Raiz del agregado: sus fases y
 * hallazgos solo cambian a traves de el, y aqui viven las reglas que antes estaban en la web.
 *
 * - Solo existe para reservas de 3 horas o mas confirmadas, en curso o terminadas.
 * - El admin lo edita mientras el vehiculo esta en el lavadero (reserva confirmada o en curso).
 * - Las 5 fases se recorren en orden fijo; registrar un hallazgo en una fase pendiente la deja en curso.
 * - El enlace publico usa un token aleatorio, nunca el id ni el codigo de la reserva.
 */
public final class InspectionReport {

    public static final int MIN_SERVICE_MINUTES = 180;
    public static final int MAX_PHOTOS_PER_FINDING = 5;
    private static final Set<String> VISIBLE_STATUSES = Set.of("CONFIRMED", "IN_PROGRESS", "COMPLETED");
    private static final Set<String> EDITABLE_STATUSES = Set.of("CONFIRMED", "IN_PROGRESS");

    /** Datos de la reserva copiados al crear el informe; sin correo ni telefono porque el enlace es publico. */
    public record BookingInfo(String code, String vehicle, String plate, String services, LocalDate date,
                              LocalTime time, int durationMinutes) {
    }

    /** Estado de una fase. */
    public record PhaseState(InspectionPhase phase, InspectionPhaseStatus status, Instant startedAt,
                             Instant finishedAt) {
    }

    /** Numeros que ve el admin y el cliente. */
    public record Summary(int phasesDone, int phasesTotal, int findings, int majorFindings, List<String> areas) {
    }

    private final Long id;
    private final long bookingId;
    private final BookingInfo booking;
    private final String publicToken;
    private final Map<InspectionPhase, PhaseState> phases;
    private final List<InspectionFinding> findings;
    private boolean published;
    private Instant publishedAt;
    private Instant updatedAt;

    private InspectionReport(Long id, long bookingId, BookingInfo booking, String publicToken,
                             Map<InspectionPhase, PhaseState> phases, List<InspectionFinding> findings,
                             boolean published, Instant publishedAt, Instant updatedAt) {
        this.id = id;
        this.bookingId = bookingId;
        this.booking = booking;
        this.publicToken = publicToken;
        this.phases = phases;
        this.findings = findings;
        this.published = published;
        this.publishedAt = publishedAt;
        this.updatedAt = updatedAt;
    }

    // ------------------------------------------------------------------ reglas sobre la reserva

    private static long durationMinutes(BookingSnapshot booking) {
        return Duration.between(booking.scheduledStart(), booking.scheduledEnd()).toMinutes();
    }

    /** La reserva admite informe: servicio largo y no cancelado ni pendiente. */
    public static boolean availableFor(BookingSnapshot booking) {
        return durationMinutes(booking) >= MIN_SERVICE_MINUTES && VISIBLE_STATUSES.contains(booking.status());
    }

    /** El informe se edita solo mientras el vehiculo esta en el lavadero. */
    public static boolean editableFor(BookingSnapshot booking) {
        return durationMinutes(booking) >= MIN_SERVICE_MINUTES && EDITABLE_STATUSES.contains(booking.status());
    }

    public static void requireEditable(BookingSnapshot booking) {
        if (!editableFor(booking)) {
            throw new ConflictException("INSPECTION_NOT_EDITABLE",
                    "The report can only be edited for long bookings that are confirmed or in progress");
        }
    }

    // ------------------------------------------------------------------ creacion

    public static InspectionReport start(BookingSnapshot booking, String publicToken, Instant now) {
        requireEditable(booking);
        Map<InspectionPhase, PhaseState> phases = new EnumMap<>(InspectionPhase.class);
        for (InspectionPhase phase : InspectionPhase.values()) {
            phases.put(phase, new PhaseState(phase, InspectionPhaseStatus.PENDING, null, null));
        }
        BookingInfo info = new BookingInfo(booking.code(), booking.vehicle(), booking.plate(), booking.servicesLabel(),
                booking.date(), booking.startTime(), (int) durationMinutes(booking));
        return new InspectionReport(null, booking.id(), info, publicToken, phases, new ArrayList<>(), false, null, now);
    }

    public static InspectionReport restore(Long id, long bookingId, BookingInfo booking, String publicToken,
                                           List<PhaseState> phases, List<InspectionFinding> findings,
                                           boolean published, Instant publishedAt, Instant updatedAt) {
        Map<InspectionPhase, PhaseState> byPhase = new EnumMap<>(InspectionPhase.class);
        for (InspectionPhase phase : InspectionPhase.values()) {
            byPhase.put(phase, new PhaseState(phase, InspectionPhaseStatus.PENDING, null, null));
        }
        phases.forEach(state -> byPhase.put(state.phase(), state));
        return new InspectionReport(id, bookingId, booking, publicToken, byPhase, new ArrayList<>(findings),
                published, publishedAt, updatedAt);
    }

    // ------------------------------------------------------------------ comportamiento

    /** Pendiente borra las horas; en curso guarda cuando empezo; terminada guarda cuando termino. */
    public void setPhaseStatus(InspectionPhase phase, InspectionPhaseStatus status, Instant now) {
        PhaseState current = phases.get(phase);
        Instant startedAt = status == InspectionPhaseStatus.PENDING ? null
                : current.startedAt() == null ? now : current.startedAt();
        Instant finishedAt = status == InspectionPhaseStatus.DONE ? now : null;
        phases.put(phase, new PhaseState(phase, status, startedAt, finishedAt));
        updatedAt = now;
    }

    /** Agrega un hallazgo (aun sin id); si la fase estaba pendiente queda en curso. */
    public InspectionFinding addFinding(InspectionPhase phase, String area, FindingSeverity severity, String note,
                                        int photoCount, Instant now) {
        if (phase == null || severity == null) {
            throw new InvalidValueException("INVALID_FINDING", "Phase and severity are required");
        }
        if (photoCount > MAX_PHOTOS_PER_FINDING) {
            throw new InvalidValueException("TOO_MANY_PHOTOS", "A finding has at most " + MAX_PHOTOS_PER_FINDING + " photos");
        }
        InspectionFinding finding = InspectionFinding.draft(phase, area, severity, note, now);
        findings.add(finding);
        PhaseState state = phases.get(phase);
        if (state.status() == InspectionPhaseStatus.PENDING) {
            phases.put(phase, new PhaseState(phase, InspectionPhaseStatus.IN_PROGRESS, now, null));
        }
        updatedAt = now;
        return finding;
    }

    public InspectionFinding removeFinding(long findingId, Instant now) {
        InspectionFinding finding = findings.stream().filter(f -> f.id() != null && f.id() == findingId).findFirst()
                .orElseThrow(() -> new NotFoundException("FINDING_NOT_FOUND", "Finding " + findingId + " does not exist"));
        findings.remove(finding);
        updatedAt = now;
        return finding;
    }

    /** Publicar deja el informe visible por su enlace; volver a publicar no cambia la fecha. */
    public void publish(Instant now) {
        if (!published) {
            published = true;
            publishedAt = now;
        }
        updatedAt = now;
    }

    public void requirePublished() {
        if (!published) {
            throw new ConflictException("INSPECTION_NOT_PUBLISHED", "Publish the report before sharing it");
        }
    }

    public Summary summary() {
        List<String> areas = findings.stream().map(InspectionFinding::area).toList();
        int major = (int) findings.stream().filter(f -> f.severity() == FindingSeverity.MAJOR).count();
        int done = (int) phases.values().stream().filter(p -> p.status() == InspectionPhaseStatus.DONE).count();
        return new Summary(done, phases.size(), findings.size(), major, areas);
    }

    /** Placa enmascarada para el enlace publico: "ABC123" -> "ABC•••". */
    public String maskedPlate() {
        String plate = booking.plate() == null ? "" : booking.plate().replaceAll("[\\s-]", "");
        return plate.length() > 3 ? plate.substring(0, 3) + "•••" : plate;
    }

    // ------------------------------------------------------------------ lectura

    public Long id() {
        return id;
    }

    public long bookingId() {
        return bookingId;
    }

    public BookingInfo booking() {
        return booking;
    }

    public String publicToken() {
        return publicToken;
    }

    /** En el orden de recorrido del vehiculo. */
    public List<PhaseState> phases() {
        return Arrays.stream(InspectionPhase.values()).map(phases::get).toList();
    }

    public List<InspectionFinding> findings() {
        return List.copyOf(findings);
    }

    public List<InspectionFinding> findingsOf(InspectionPhase phase) {
        return findings.stream().filter(f -> f.phase() == phase).toList();
    }

    public boolean published() {
        return published;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
