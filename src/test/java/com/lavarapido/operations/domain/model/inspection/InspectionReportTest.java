package com.lavarapido.operations.domain.model.inspection;

import com.lavarapido.operations.domain.exception.ConflictException;
import com.lavarapido.operations.domain.exception.InvalidValueException;
import com.lavarapido.operations.domain.model.BookingSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Informe de inspección del vehículo")
class InspectionReportTest {

    private static final Instant NOW = Instant.parse("2026-10-14T15:00:00Z");

    // reserva de las 9:00 de Bogotá con la duración y el estado indicados
    private static BookingSnapshot booking(int minutes, String status) {
        Instant start = Instant.parse("2026-10-14T14:00:00Z");
        return new BookingSnapshot(7, "RES-000007", status, LocalDate.of(2026, 10, 14), LocalTime.of(9, 0),
                LocalTime.of(9, 0).plusMinutes(minutes), start, start.plusSeconds(minutes * 60L), 10002L,
                "Mazda 3", "ABC-123", new BigDecimal("120000"), List.of(new BookingSnapshot.Line(70, "Full")), null);
    }

    @Test
    @DisplayName("solo servicios de 3 horas o más; se edita confirmado o en curso y se ve también terminado")
    void availability() {
        assertFalse(InspectionReport.availableFor(booking(120, "CONFIRMED")));
        assertTrue(InspectionReport.editableFor(booking(180, "CONFIRMED")));
        assertTrue(InspectionReport.editableFor(booking(240, "IN_PROGRESS")));
        assertTrue(InspectionReport.availableFor(booking(240, "COMPLETED")));
        assertFalse(InspectionReport.editableFor(booking(240, "COMPLETED")));
        assertFalse(InspectionReport.availableFor(booking(240, "CANCELLED")));
        assertThrows(ConflictException.class, () -> InspectionReport.start(booking(240, "COMPLETED"), "t", NOW));
    }

    @Test
    @DisplayName("arranca con las 5 fases pendientes y un hallazgo deja su fase en curso")
    void findingStartsThePhase() {
        InspectionReport report = InspectionReport.start(booking(240, "IN_PROGRESS"), "token", NOW);
        assertEquals(5, report.phases().size());
        assertTrue(report.phases().stream().allMatch(p -> p.status() == InspectionPhaseStatus.PENDING));

        report.addFinding(InspectionPhase.EXTERIOR, "  Puerta trasera  ", FindingSeverity.MAJOR, "Rayón", 2, NOW);
        InspectionReport.PhaseState exterior = report.phases().get(1);
        assertEquals(InspectionPhaseStatus.IN_PROGRESS, exterior.status());
        assertEquals(NOW, exterior.startedAt());
        assertEquals("Puerta trasera", report.findings().get(0).area());
        assertEquals(1, report.summary().majorFindings());
        assertEquals(List.of("Puerta trasera"), report.summary().areas());
    }

    @Test
    @DisplayName("terminar una fase guarda la hora; volverla a pendiente borra las horas")
    void phaseTimes() {
        InspectionReport report = InspectionReport.start(booking(240, "IN_PROGRESS"), "token", NOW);
        report.setPhaseStatus(InspectionPhase.RECEPTION, InspectionPhaseStatus.DONE, NOW);
        assertNotNull(report.phases().get(0).finishedAt());
        assertEquals(1, report.summary().phasesDone());
        report.setPhaseStatus(InspectionPhase.RECEPTION, InspectionPhaseStatus.PENDING, NOW);
        assertNull(report.phases().get(0).startedAt());
        assertNull(report.phases().get(0).finishedAt());
    }

    @Test
    @DisplayName("rechaza hallazgos sin parte del vehículo o con más de 5 fotos")
    void invalidFindings() {
        InspectionReport report = InspectionReport.start(booking(240, "IN_PROGRESS"), "token", NOW);
        assertThrows(InvalidValueException.class,
                () -> report.addFinding(InspectionPhase.INTERIOR, " ", FindingSeverity.INFO, "", 0, NOW));
        assertThrows(InvalidValueException.class,
                () -> report.addFinding(InspectionPhase.INTERIOR, "Silla", FindingSeverity.INFO, "", 6, NOW));
    }

    @Test
    @DisplayName("solo se comparte publicado; publicar dos veces conserva la fecha; la placa pública va enmascarada")
    void publishing() {
        InspectionReport report = InspectionReport.start(booking(240, "IN_PROGRESS"), "token", NOW);
        assertThrows(ConflictException.class, report::requirePublished);
        report.publish(NOW);
        report.publish(NOW.plusSeconds(60));
        assertEquals(NOW, report.publishedAt());
        assertEquals("ABC•••", report.maskedPlate());
    }
}
