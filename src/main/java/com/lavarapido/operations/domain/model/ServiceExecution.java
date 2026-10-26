package com.lavarapido.operations.domain.model;

import com.lavarapido.operations.domain.exception.ConflictException;
import com.lavarapido.operations.domain.exception.InvalidValueException;

import java.time.Instant;
import java.util.Objects;

/**
 * Ejecucion de UNA linea de la reserva (execution.service_execution): quien la hace, cuando
 * empezo y termino, y la calificacion del cliente. La calificacion vive aqui y no en otra tabla
 * porque no existe sin la linea que califica (models.md, decision 3).
 */
public final class ServiceExecution {

    private static final int MAX_COMMENT = 500;

    private Long id;
    private final long bookingServiceId;
    private int operatorId;
    private ExecutionStatus status;
    private Instant startedAt;
    private Instant finishedAt;
    private Short rating;
    private String comment;
    private Instant ratedAt;
    private boolean commentVisible;
    // cuándo se avisó que el servicio terminó sin registrarse (no iniciado o no finalizado)
    private Instant followUpNoticeAt;
    // B-01: indicaciones del administrador al asignar (por ejemplo "llevar cera extra")
    private String assignmentNotes;
    private static final int MAX_ASSIGNMENT_NOTES = 300;

    private ServiceExecution(Long id, long bookingServiceId, int operatorId, ExecutionStatus status,
                             Instant startedAt, Instant finishedAt, Short rating, String comment,
                             Instant ratedAt, boolean commentVisible, Instant followUpNoticeAt) {
        this.id = id;
        this.bookingServiceId = bookingServiceId;
        this.operatorId = operatorId;
        this.status = Objects.requireNonNull(status, "status");
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.rating = rating;
        this.comment = comment;
        this.ratedAt = ratedAt;
        this.commentVisible = commentVisible;
        this.followUpNoticeAt = followUpNoticeAt;
    }

    /** Linea asignada a un operario: queda pendiente hasta que la empiece. */
    public static ServiceExecution assign(long bookingServiceId, int operatorId) {
        return new ServiceExecution(null, bookingServiceId, operatorId, ExecutionStatus.PENDING,
                null, null, null, null, null, true, null);
    }

    /** B-01: asignada con las notas del administrador (vacías = sin notas). */
    public static ServiceExecution assign(long bookingServiceId, int operatorId, String notes) {
        ServiceExecution execution = assign(bookingServiceId, operatorId);
        execution.assignmentNotes = cleanNotes(notes);
        return execution;
    }

    public static ServiceExecution restore(long id, long bookingServiceId, int operatorId, ExecutionStatus status,
                                           Instant startedAt, Instant finishedAt, Short rating, String comment,
                                           Instant ratedAt, boolean commentVisible) {
        return restore(id, bookingServiceId, operatorId, status, startedAt, finishedAt, rating, comment, ratedAt,
                commentVisible, null);
    }

    public static ServiceExecution restore(long id, long bookingServiceId, int operatorId, ExecutionStatus status,
                                           Instant startedAt, Instant finishedAt, Short rating, String comment,
                                           Instant ratedAt, boolean commentVisible, Instant followUpNoticeAt) {
        return new ServiceExecution(id, bookingServiceId, operatorId, status, startedAt, finishedAt, rating,
                comment, ratedAt, commentVisible, followUpNoticeAt);
    }

    /** Las notas guardadas (al leer de la base). */
    public void restoreAssignmentNotes(String notes) {
        this.assignmentNotes = notes;
    }

    /**
     * Cambiar de operario solo mientras no ha empezado. B-01: las notas se reemplazan solo si llegan
     * notas nuevas; sin notas (o en blanco) se conservan las que ya tenía la asignación.
     */
    public void reassign(int newOperatorId, String notes) {
        String clean = cleanNotes(notes);
        reassign(newOperatorId);
        if (clean != null) {
            assignmentNotes = clean;
        }
    }

    private static String cleanNotes(String notes) {
        if (notes == null || notes.isBlank()) {
            return null;
        }
        String clean = notes.strip();
        if (clean.length() > MAX_ASSIGNMENT_NOTES) {
            throw new InvalidValueException("INVALID_ASSIGNMENT_NOTES",
                    "The assignment notes can have at most " + MAX_ASSIGNMENT_NOTES + " characters");
        }
        return clean;
    }

    public String assignmentNotes() {
        return assignmentNotes;
    }

    /** Cambiar de operario solo mientras no ha empezado. */
    public void reassign(int newOperatorId) {
        if (status != ExecutionStatus.PENDING) {
            throw new ConflictException("EXECUTION_ALREADY_STARTED", "The service already started");
        }
        operatorId = newOperatorId;
    }

    public void start(Instant now) {
        if (status != ExecutionStatus.PENDING) {
            throw new ConflictException("EXECUTION_ALREADY_STARTED", "The service already started");
        }
        status = ExecutionStatus.IN_PROGRESS;
        startedAt = now;
        // ya se registró: si luego no lo finaliza, ese es otro aviso
        followUpNoticeAt = null;
    }

    public void finish(Instant now) {
        if (status != ExecutionStatus.IN_PROGRESS) {
            throw new ConflictException("EXECUTION_NOT_STARTED", "The service has not started");
        }
        status = ExecutionStatus.COMPLETED;
        finishedAt = now;
    }

    /**
     * El admin confirma que se atendió aunque el operario no lo registró. Queda terminada sin
     * inventar horas: si nunca se inició no hay hora de inicio (no cuenta para la puntualidad) y la de
     * fin queda vacía (no suma horas trabajadas), pero sí cuenta como servicio hecho.
     */
    public void closeAsAttended() {
        if (status == ExecutionStatus.COMPLETED) {
            throw new ConflictException("EXECUTION_ALREADY_COMPLETED", "The service is already completed");
        }
        status = ExecutionStatus.COMPLETED;
    }

    /** Deja constancia de que ya se avisó que terminó sin registrarse, para no repetirlo. */
    public void followUpNoticeSent(Instant now) {
        followUpNoticeAt = now;
    }

    /** Calificacion del cliente (RF-012): 1 a 5, una sola vez, solo de un servicio terminado. */
    public void rate(int stars, String text, Instant now) {
        if (status != ExecutionStatus.COMPLETED) {
            throw new ConflictException("SERVICE_NOT_COMPLETED", "Only a completed service can be rated");
        }
        if (rating != null) {
            throw new ConflictException("ALREADY_RATED", "This service was already rated");
        }
        if (stars < 1 || stars > 5) {
            throw new InvalidValueException("INVALID_RATING", "The rating must be between 1 and 5");
        }
        String clean = text == null || text.isBlank() ? null : text.trim();
        if (clean != null && clean.length() > MAX_COMMENT) {
            throw new InvalidValueException("COMMENT_TOO_LONG", "The comment is too long");
        }
        rating = (short) stars;
        comment = clean;
        ratedAt = now;
    }

    public void assignId(long newId) {
        if (id != null) {
            throw new IllegalStateException("The execution already has an id");
        }
        id = newId;
    }

    public Long id() {
        return id;
    }

    public long bookingServiceId() {
        return bookingServiceId;
    }

    public int operatorId() {
        return operatorId;
    }

    public ExecutionStatus status() {
        return status;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public Short rating() {
        return rating;
    }

    public String comment() {
        return comment;
    }

    public Instant ratedAt() {
        return ratedAt;
    }

    public boolean commentVisible() {
        return commentVisible;
    }

    public Instant followUpNoticeAt() {
        return followUpNoticeAt;
    }
}
