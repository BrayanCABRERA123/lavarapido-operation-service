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

    private ServiceExecution(Long id, long bookingServiceId, int operatorId, ExecutionStatus status,
                             Instant startedAt, Instant finishedAt, Short rating, String comment,
                             Instant ratedAt, boolean commentVisible) {
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
    }

    /** Linea asignada a un operario: queda pendiente hasta que la empiece. */
    public static ServiceExecution assign(long bookingServiceId, int operatorId) {
        return new ServiceExecution(null, bookingServiceId, operatorId, ExecutionStatus.PENDING,
                null, null, null, null, null, true);
    }

    public static ServiceExecution restore(long id, long bookingServiceId, int operatorId, ExecutionStatus status,
                                           Instant startedAt, Instant finishedAt, Short rating, String comment,
                                           Instant ratedAt, boolean commentVisible) {
        return new ServiceExecution(id, bookingServiceId, operatorId, status, startedAt, finishedAt, rating,
                comment, ratedAt, commentVisible);
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
    }

    public void finish(Instant now) {
        if (status != ExecutionStatus.IN_PROGRESS) {
            throw new ConflictException("EXECUTION_NOT_STARTED", "The service has not started");
        }
        status = ExecutionStatus.COMPLETED;
        finishedAt = now;
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
}
