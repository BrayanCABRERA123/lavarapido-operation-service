package com.lavarapido.operations.application.usecase;

import com.lavarapido.operations.domain.event.ExecutionEvent;
import com.lavarapido.operations.domain.exception.ConflictException;
import com.lavarapido.operations.domain.exception.DomainException;
import com.lavarapido.operations.domain.exception.NotFoundException;
import com.lavarapido.operations.domain.model.BookingSnapshot;
import com.lavarapido.operations.domain.model.ExecutionStatus;
import com.lavarapido.operations.domain.model.Operator;
import com.lavarapido.operations.domain.model.ServiceExecution;
import com.lavarapido.operations.domain.port.in.FollowUpUseCase;
import com.lavarapido.operations.domain.port.out.BookingDirectory;
import com.lavarapido.operations.domain.port.out.DomainEventPublisher;
import com.lavarapido.operations.domain.port.out.ExecutionRepository;
import com.lavarapido.operations.domain.port.out.OperatorRepository;
import com.lavarapido.operations.domain.service.FollowUpPolicy;
import com.lavarapido.operations.domain.service.FollowUpPolicy.Reason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reservas que terminaron y nadie cerró (FollowUpPolicy). Un operario puede atender y olvidar darle
 * "Iniciar": por eso una reserva con operario nunca se marca sola como "no asistió", la decide el
 * administrador. Solo la que nunca tuvo operario se cierra sola: ahí nadie la pudo atender.
 */
@Service
@Transactional
public class FollowUpService implements FollowUpUseCase {

    private static final Logger log = LoggerFactory.getLogger(FollowUpService.class);
    private static final long SYSTEM_ACTOR = 0L;

    private final ExecutionRepository executions;
    private final BookingDirectory bookings;
    private final OperatorRepository operators;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final ZoneId zone;
    private final Duration grace;
    // cuántos días atrás se revisan: el servidor se enciende a demanda y puede pasar semanas apagado.
    // No más de lo que cargan la web (30) y el móvil (29), o la tarjeta "Por cerrar" no las mostraría
    private final int lookBackDays;

    public FollowUpService(ExecutionRepository executions, BookingDirectory bookings, OperatorRepository operators,
                           DomainEventPublisher events, Clock clock, ZoneId zone,
                           @Value("${app.follow-up.grace-minutes:60}") long graceMinutes,
                           @Value("${app.follow-up.look-back-days:28}") int lookBackDays) {
        this.executions = executions;
        this.bookings = bookings;
        this.operators = operators;
        this.events = events;
        this.clock = clock;
        this.zone = zone;
        this.grace = Duration.ofMinutes(graceMinutes);
        this.lookBackDays = lookBackDays;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ToCloseView> toClose(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate first = from == null ? today.minusDays(lookBackDays) : from;
        LocalDate last = to == null ? today : to;
        Instant now = clock.instant();
        List<BookingSnapshot> list = bookings.forAdmin(first, last);
        Map<Long, ServiceExecution> byLine = byLine(list);
        List<ToCloseView> result = new ArrayList<>();
        for (BookingSnapshot booking : list.stream().sorted(Comparator.comparing(BookingSnapshot::scheduledStart)).toList()) {
            List<ServiceExecution> lines = linesOf(booking, byLine);
            FollowUpPolicy.of(booking, lines, now, grace)
                    .filter(reason -> reason != Reason.NO_OPERATOR)
                    .ifPresent(reason -> result.add(new ToCloseView(booking.id(), reason, lines.getFirst().operatorId())));
        }
        return result;
    }

    @Override
    public void closeAsAttended(long bookingId, long adminId) {
        BookingSnapshot booking = bookings.forAdmin(bookingId)
                .orElseThrow(() -> new NotFoundException("BOOKING_NOT_FOUND", "Booking " + bookingId + " does not exist"));
        List<ServiceExecution> lines = executions.findByLineIds(booking.lineIds());
        Optional<Reason> reason = FollowUpPolicy.of(booking, lines, clock.instant(), grace);
        if (reason.isEmpty() || reason.get() == Reason.NO_OPERATOR) {
            throw new ConflictException("BOOKING_NOT_TO_CLOSE", "Only a finished booking with an operator can be closed");
        }
        List<ServiceExecution> open = lines.stream().filter(line -> line.status() != ExecutionStatus.COMPLETED).toList();
        open.forEach(ServiceExecution::closeAsAttended);
        // OPS-01: las líneas se escriben antes de mover la reserva (si booking falla, se deshace todo)
        executions.saveAll(open, adminId);
        executions.flush();
        // una reserva que booking ya tiene COMPLETED solo necesita cerrar sus líneas
        if (!"COMPLETED".equals(booking.status())) {
            // booking solo avanza paso a paso: CONFIRMED -> IN_PROGRESS -> COMPLETED
            if (reason.get() == Reason.NOT_STARTED) {
                bookings.advance(booking.id(), "IN_PROGRESS");
            }
            bookings.advance(booking.id(), "COMPLETED");
        }
        // sin ServiceCompleted: al cliente le diría "ya puedes recogerlo" horas después
    }

    @Override
    public FollowUpRun followUp() {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Instant now = clock.instant();
        List<BookingSnapshot> recent = bookings.forService(today.minusDays(lookBackDays), today);
        Map<Long, ServiceExecution> byLine = byLine(recent);
        int closed = 0;
        int notices = 0;
        for (BookingSnapshot booking : recent) {
            List<ServiceExecution> lines = linesOf(booking, byLine);
            Optional<Reason> reason = FollowUpPolicy.of(booking, lines, now, grace);
            if (reason.isEmpty()) {
                continue;
            }
            if (reason.get() == Reason.NO_OPERATOR) {
                try {
                    bookings.markUnattended(booking.id());
                    closed++;
                } catch (DomainException notClosed) {
                    // otro la cambió o booking aún no la ve vencida: se intenta en la siguiente vuelta
                    log.warn("Booking {} was not closed as not attended: {} {}", booking.id(), notClosed.code(),
                            notClosed.getMessage());
                }
            } else if (lines.stream().allMatch(line -> line.followUpNoticeAt() == null)) {
                notice(booking, lines, reason.get(), now);
                notices++;
            }
        }
        return new FollowUpRun(closed, notices);
    }

    // un solo aviso por reserva: al operario y a los administradores (notification-service)
    private void notice(BookingSnapshot booking, List<ServiceExecution> lines, Reason reason, Instant now) {
        Long operatorUserId = operators.findById(lines.getFirst().operatorId()).map(Operator::userId).orElse(null);
        ExecutionEvent.Type type = reason == Reason.NOT_STARTED
                ? ExecutionEvent.Type.SERVICE_NOT_STARTED : ExecutionEvent.Type.SERVICE_NOT_FINISHED;
        lines.forEach(line -> line.followUpNoticeSent(now));
        executions.saveAll(lines, SYSTEM_ACTOR);
        events.publish(new ExecutionEvent(type, booking.id(), booking.code(), booking.ownerUserId(), operatorUserId,
                booking.scheduledStart(), now));
    }

    private Map<Long, ServiceExecution> byLine(List<BookingSnapshot> list) {
        return executions.findByLineIds(list.stream().flatMap(booking -> booking.lineIds().stream()).toList())
                .stream().collect(Collectors.toMap(ServiceExecution::bookingServiceId, Function.identity()));
    }

    private static List<ServiceExecution> linesOf(BookingSnapshot booking, Map<Long, ServiceExecution> byLine) {
        return booking.lineIds().stream().map(byLine::get).filter(line -> line != null).toList();
    }
}
