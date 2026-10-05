package com.lavarapido.operations.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Ejecucion de una linea de reserva. booking_service_id apunta a booking (sin FK, otro servicio). */
@Entity
@Table(schema = "execution", name = "service_execution")
public class ServiceExecutionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "service_execution_id")
    private Long id;

    @Column(name = "booking_service_id")
    private Long bookingServiceId;

    @Column(name = "operator_id")
    private Integer operatorId;

    @Column(name = "execution_status_id")
    private Short statusId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "quality_rating")
    private Short rating;

    @Column(name = "quality_comment", length = 500)
    private String comment;

    @Column(name = "rated_at")
    private Instant ratedAt;

    @Column(name = "is_comment_visible")
    private Boolean commentVisible;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

    @Column(name = "row_version", insertable = false, updatable = false)
    private Integer rowVersion;

    public Long getId() {
        return id;
    }

    public Long getBookingServiceId() {
        return bookingServiceId;
    }

    public void setBookingServiceId(Long bookingServiceId) {
        this.bookingServiceId = bookingServiceId;
    }

    public Integer getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Integer operatorId) {
        this.operatorId = operatorId;
    }

    public Short getStatusId() {
        return statusId;
    }

    public void setStatusId(Short statusId) {
        this.statusId = statusId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Short getRating() {
        return rating;
    }

    public void setRating(Short rating) {
        this.rating = rating;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Instant getRatedAt() {
        return ratedAt;
    }

    public void setRatedAt(Instant ratedAt) {
        this.ratedAt = ratedAt;
    }

    public Boolean getCommentVisible() {
        return commentVisible;
    }

    public void setCommentVisible(Boolean commentVisible) {
        this.commentVisible = commentVisible;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }
}
