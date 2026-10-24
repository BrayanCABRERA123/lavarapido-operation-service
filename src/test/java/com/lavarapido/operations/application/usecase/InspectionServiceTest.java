package com.lavarapido.operations.application.usecase;

import com.lavarapido.operations.domain.exception.ConflictException;
import com.lavarapido.operations.domain.exception.DependencyUnavailableException;
import com.lavarapido.operations.domain.exception.InvalidValueException;
import com.lavarapido.operations.domain.exception.NotFoundException;
import com.lavarapido.operations.domain.model.BookingSnapshot;
import com.lavarapido.operations.domain.model.inspection.InspectionReport;
import com.lavarapido.operations.domain.port.in.InspectionUseCase.InspectionView;
import com.lavarapido.operations.domain.port.in.InspectionUseCase.PhotoUpload;
import com.lavarapido.operations.domain.port.out.BookingDirectory;
import com.lavarapido.operations.domain.port.out.DomainEventPublisher;
import com.lavarapido.operations.domain.port.out.InspectionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("InspectionService (informe de inspección, F-05)")
class InspectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T15:00:00Z");
    private static final long ADMIN = 1L;

    private final InspectionRepository inspections = mock(InspectionRepository.class);
    private final BookingDirectory bookings = mock(BookingDirectory.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final InspectionService service = new InspectionService(inspections, bookings, events,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static BookingSnapshot booking(int minutes, String status) {
        Instant start = Instant.parse("2026-10-14T14:00:00Z");
        return new BookingSnapshot(7, "RES-000007", status, LocalDate.of(2026, 10, 14), LocalTime.of(9, 0),
                LocalTime.of(9, 0).plusMinutes(minutes), start, start.plusSeconds(minutes * 60L), 10002L,
                "Mazda 3", "ABC-123", new BigDecimal("120000"), List.of(new BookingSnapshot.Line(70, "Full")), null);
    }

    @Test
    @DisplayName("iniciar crea el informe una sola vez; si ya existe devuelve el mismo")
    void startCreatesTheReportOnce() {
        given(bookings.forAdmin(7L)).willReturn(Optional.of(booking(180, "CONFIRMED")));
        given(inspections.findByBooking(7L)).willReturn(Optional.empty());
        given(inspections.save(any(), anyLong())).willAnswer(invocation -> invocation.getArgument(0));

        InspectionView created = service.start(7L, ADMIN);

        assertTrue(created.available());
        assertTrue(created.editable());
        verify(inspections).save(any(), anyLong());

        given(inspections.findByBooking(7L)).willReturn(Optional.of(created.report()));
        assertSame(created.report(), service.start(7L, ADMIN).report());
    }

    @Test
    @DisplayName("un servicio de menos de 180 minutos no admite informe")
    void shortServicesHaveNoInspection() {
        given(bookings.forAdmin(7L)).willReturn(Optional.of(booking(60, "CONFIRMED")));
        given(inspections.findByBooking(7L)).willReturn(Optional.empty());

        InspectionView view = service.forBooking(7L);

        assertFalse(view.available());
        assertFalse(view.editable());
    }

    @Test
    @DisplayName("una foto que no es JPEG/PNG o pesa más de 2 MB se rechaza antes de guardar")
    void invalidPhotosAreRejected() {
        PhotoUpload gif = new PhotoUpload("image/gif", new byte[10]);
        PhotoUpload huge = new PhotoUpload("image/jpeg", new byte[2 * 1024 * 1024 + 1]);

        assertThrows(InvalidValueException.class, () -> service.addFinding(7L, null, "Puerta", null, null, List.of(gif), ADMIN));
        assertThrows(InvalidValueException.class, () -> service.addFinding(7L, null, "Puerta", null, null, List.of(huge), ADMIN));
        verify(inspections, never()).save(any(), anyLong());
    }

    @Test
    @DisplayName("compartir exige el informe publicado y avisa al cliente con el enlace")
    void shareNeedsAPublishedReport() {
        BookingSnapshot booking = booking(180, "IN_PROGRESS");
        InspectionReport report = InspectionReport.start(booking, "token-publico", NOW);
        given(bookings.forAdmin(7L)).willReturn(Optional.of(booking));
        given(inspections.findByBooking(7L)).willReturn(Optional.of(report));

        ConflictException notPublished = assertThrows(ConflictException.class, () -> service.share(7L, ADMIN));
        assertEquals("INSPECTION_NOT_PUBLISHED", notPublished.code());

        report.publish(NOW);
        service.share(7L, ADMIN);
        verify(events).publishInspectionShared(any());
    }

    @Test
    @DisplayName("OPS-08: si el aviso no se pudo publicar, compartir falla con 503 en vez de decir que se envió")
    void shareFailsWhenTheEventIsNotPublished() {
        BookingSnapshot booking = booking(180, "IN_PROGRESS");
        InspectionReport report = InspectionReport.start(booking, "token-publico", NOW);
        report.publish(NOW);
        given(bookings.forAdmin(7L)).willReturn(Optional.of(booking));
        given(inspections.findByBooking(7L)).willReturn(Optional.of(report));
        willThrow(new DependencyUnavailableException("NOTIFICATION_NOT_SENT", "RabbitMQ is down"))
                .given(events).publishInspectionShared(any());

        DependencyUnavailableException error = assertThrows(DependencyUnavailableException.class,
                () -> service.share(7L, ADMIN));
        assertEquals("NOTIFICATION_NOT_SENT", error.code());
    }

    @Test
    @DisplayName("una reserva sin cuenta de cliente no se comparte (no hay a quién avisar)")
    void shareNeedsAClientAccount() {
        Instant start = Instant.parse("2026-10-14T14:00:00Z");
        BookingSnapshot withoutClient = new BookingSnapshot(8, "RES-000008", "IN_PROGRESS", LocalDate.of(2026, 10, 14),
                LocalTime.of(9, 0), LocalTime.of(12, 0), start, start.plusSeconds(3 * 3600), null, "Mazda 3", "ABC-123",
                new BigDecimal("120000"), List.of(new BookingSnapshot.Line(80, "Full")), null);
        InspectionReport report = InspectionReport.start(withoutClient, "token", NOW);
        report.publish(NOW);
        given(bookings.forAdmin(8L)).willReturn(Optional.of(withoutClient));
        given(inspections.findByBooking(8L)).willReturn(Optional.of(report));

        assertEquals("BOOKING_WITHOUT_CLIENT",
                assertThrows(InvalidValueException.class, () -> service.share(8L, ADMIN)).code());
        verify(events, never()).publishInspectionShared(any());
    }

    @Test
    @DisplayName("el enlace público solo abre un informe publicado")
    void publicLinkOnlyOpensPublishedReports() {
        InspectionReport draft = InspectionReport.start(booking(180, "CONFIRMED"), "borrador", NOW);
        given(inspections.findByPublicToken("borrador")).willReturn(Optional.of(draft));

        assertThrows(NotFoundException.class, () -> service.published("borrador"));

        draft.publish(NOW);
        assertSame(draft, service.published("borrador"));
    }
}
