package com.lavarapido.operations.application.usecase;

import com.lavarapido.operations.domain.event.ExecutionEvent;
import com.lavarapido.operations.domain.exception.ConflictException;
import com.lavarapido.operations.domain.exception.InvalidValueException;
import com.lavarapido.operations.domain.exception.NotFoundException;
import com.lavarapido.operations.domain.model.Absence;
import com.lavarapido.operations.domain.model.BookingSnapshot;
import com.lavarapido.operations.domain.model.ExecutionStatus;
import com.lavarapido.operations.domain.model.Operator;
import com.lavarapido.operations.domain.model.ServiceExecution;
import com.lavarapido.operations.domain.model.WeeklyAvailability;
import com.lavarapido.operations.domain.port.in.OperationsUseCase;
import com.lavarapido.operations.domain.port.out.BookingDirectory;
import com.lavarapido.operations.domain.port.out.DomainEventPublisher;
import com.lavarapido.operations.domain.port.out.ExecutionRepository;
import com.lavarapido.operations.domain.port.out.OperatorRepository;
import com.lavarapido.operations.domain.port.out.UserDirectory;
import com.lavarapido.operations.domain.port.out.UserDirectory.UserSnapshot;
import com.lavarapido.operations.domain.service.AssignmentPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Operarios, asignacion, ejecucion y calificaciones (RF-008 a RF-012). Las reservas son de
 * booking-service y las cuentas de security-service: aqui solo se guarda quien hace cada linea,
 * como va y como la califico el cliente (esquema execution).
 */
@Service
@Transactional
public class OperationsService implements OperationsUseCase {

    // estados de reserva en los que se puede asignar o cambiar el operario
    private static final Set<String> ASSIGNABLE = Set.of("SCHEDULED", "CONFIRMED");
    // historial de calificaciones del operario: lo que booking deja listar por consulta
    private static final int RATINGS_DAYS = 60;

    private final OperatorRepository operators;
    private final ExecutionRepository executions;
    private final BookingDirectory bookings;
    private final UserDirectory users;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final ZoneId zone;

    public OperationsService(OperatorRepository operators, ExecutionRepository executions, BookingDirectory bookings,
                             UserDirectory users, DomainEventPublisher events, Clock clock, ZoneId zone) {
        this.operators = operators;
        this.executions = executions;
        this.bookings = bookings;
        this.users = users;
        this.events = events;
        this.clock = clock;
        this.zone = zone;
    }

    // ================================================================== admin

    @Override
    public List<OperatorView> operators() {
        Map<Long, Operator> byUser = operators.findAll().stream()
                .collect(Collectors.toMap(Operator::userId, Function.identity()));
        List<OperatorView> result = new ArrayList<>();
        for (UserSnapshot user : users.operators()) {
            // una cuenta con rol OPERATOR que aun no tiene fila de operario la recibe aqui
            Operator operator = byUser.containsKey(user.id()) ? byUser.get(user.id())
                    : operators.save(Operator.provision(user.id(), today()), 0L);
            result.add(view(operator, user));
        }
        result.sort(Comparator.comparing(OperatorView::fullName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    @Override
    public OperatorView setActive(int operatorId, boolean active, long adminId) {
        Operator operator = requireOperator(operatorId);
        operator.setActive(active);
        operators.save(operator, adminId);
        return view(operator, userOf(operator));
    }

    @Override
    public OperatorView setAvailability(int operatorId, List<WeeklyAvailability> week, long adminId) {
        Operator operator = requireOperator(operatorId);
        long distinctDays = week.stream().map(WeeklyAvailability::dayOfWeek).distinct().count();
        if (distinctDays != week.size()) {
            throw new InvalidValueException("DUPLICATED_DAY", "Each day can appear only once");
        }
        operators.replaceAvailability(operatorId, week, adminId);
        return view(operator, userOf(operator));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Absence> absences(int operatorId, LocalDate from, LocalDate to) {
        requireOperator(operatorId);
        LocalDate first = from == null ? today().minusDays(30) : from;
        LocalDate last = to == null ? today().plusDays(90) : to;
        return operators.absences(operatorId, toInstant(first.atStartOfDay()), toInstant(last.plusDays(1).atStartOfDay()));
    }

    @Override
    public Absence addAbsence(int operatorId, LocalDateTime startsAt, LocalDateTime endsAt, String reason, long adminId) {
        requireOperator(operatorId);
        if (startsAt == null || endsAt == null) {
            throw new InvalidValueException("INVALID_RANGE", "Start and end are required");
        }
        Absence absence = new Absence(null, toInstant(startsAt), toInstant(endsAt), reason);
        boolean overlaps = operators.absences(operatorId, absence.startsAt(), absence.endsAt()).stream()
                .anyMatch(other -> other.overlaps(absence.startsAt(), absence.endsAt()));
        if (overlaps) {
            throw new ConflictException("ABSENCE_OVERLAP", "The operator already has an absence in that period");
        }
        return operators.addAbsence(operatorId, absence, adminId);
    }

    @Override
    public void removeAbsence(int operatorId, int absenceId, long adminId) {
        if (!operators.removeAbsence(operatorId, absenceId, adminId)) {
            throw new NotFoundException("ABSENCE_NOT_FOUND", "Absence not found");
        }
    }

    @Override
    public AssignmentView assign(long bookingId, int operatorId, long adminId) {
        BookingSnapshot booking = bookings.forAdmin(bookingId)
                .orElseThrow(() -> new NotFoundException("BOOKING_NOT_FOUND", "Booking not found"));
        if (!ASSIGNABLE.contains(booking.status())) {
            throw new ConflictException("BOOKING_NOT_ASSIGNABLE", "Only a booking that has not started can be assigned");
        }
        Operator operator = requireOperator(operatorId);

        List<BookingSnapshot> sameDay = otherBookingsSameDay(booking);
        AssignmentPolicy.check(operator, operators.availability(operatorId),
                operators.absences(operatorId, booking.scheduledStart(), booking.scheduledEnd()), booking,
                busyWith(sameDay, executionsOf(sameDay), operatorId));

        Map<Long, ServiceExecution> existing = executions.findByLineIds(booking.lineIds()).stream()
                .collect(Collectors.toMap(ServiceExecution::bookingServiceId, Function.identity()));
        List<ServiceExecution> toSave = new ArrayList<>();
        for (long lineId : booking.lineIds()) {
            ServiceExecution execution = existing.get(lineId);
            if (execution == null) {
                toSave.add(ServiceExecution.assign(lineId, operatorId));
            } else {
                execution.reassign(operatorId);
                toSave.add(execution);
            }
        }
        executions.saveAll(toSave, adminId);

        events.publish(new ExecutionEvent(ExecutionEvent.Type.OPERATOR_ASSIGNED, booking.id(), booking.code(),
                booking.ownerUserId(), operator.userId(), booking.scheduledStart(), clock.instant()));
        return new AssignmentView(booking.id(), operatorId, nameOf(operator), ExecutionStatus.PENDING, booking.bay());
    }

    @Override
    public List<CandidateView> candidates(long bookingId) {
        BookingSnapshot booking = bookings.forAdmin(bookingId)
                .orElseThrow(() -> new NotFoundException("BOOKING_NOT_FOUND", "Booking not found"));
        if (!ASSIGNABLE.contains(booking.status())) {
            throw new ConflictException("BOOKING_NOT_ASSIGNABLE", "Only a booking that has not started can be assigned");
        }
        List<BookingSnapshot> sameDay = otherBookingsSameDay(booking);
        List<ServiceExecution> sameDayExecutions = executionsOf(sameDay);
        Set<Integer> current = executions.findByLineIds(booking.lineIds()).stream()
                .map(ServiceExecution::operatorId).collect(Collectors.toSet());

        List<CandidateView> result = new ArrayList<>();
        for (OperatorView view : operators()) {
            int id = view.operatorId();
            String reason = AssignmentPolicy.blockingReason(requireOperator(id), operators.availability(id),
                    operators.absences(id, booking.scheduledStart(), booking.scheduledEnd()), booking,
                    busyWith(sameDay, sameDayExecutions, id));
            result.add(new CandidateView(id, view.fullName(), view.averageRating(), view.ratingsCount(),
                    current.contains(id), reason));
        }
        // primero los que se pueden asignar
        result.sort(Comparator.comparing((CandidateView c) -> c.unavailableReason() != null)
                .thenComparing(CandidateView::fullName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** reservas de ese dia, sin esta, que todavia ocupan a alguien */
    private List<BookingSnapshot> otherBookingsSameDay(BookingSnapshot booking) {
        return bookings.forAdmin(booking.date(), booking.date()).stream()
                .filter(other -> other.id() != booking.id() && assignableOrRunning(other))
                .toList();
    }

    private List<ServiceExecution> executionsOf(List<BookingSnapshot> list) {
        return executions.findByLineIds(list.stream().flatMap(b -> b.lineIds().stream()).toList());
    }

    /** reservas de la lista que ya tiene el operario */
    private static List<BookingSnapshot> busyWith(List<BookingSnapshot> sameDay, List<ServiceExecution> sameDayExecutions,
                                                  int operatorId) {
        Set<Long> operatorLines = sameDayExecutions.stream().filter(e -> e.operatorId() == operatorId)
                .map(ServiceExecution::bookingServiceId).collect(Collectors.toSet());
        return sameDay.stream().filter(other -> other.lineIds().stream().anyMatch(operatorLines::contains)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssignmentView> assignments(LocalDate from, LocalDate to) {
        LocalDate first = from == null ? today() : from;
        LocalDate last = to == null ? first : to;
        List<BookingSnapshot> list = bookings.forAdmin(first, last);
        Map<Long, ServiceExecution> byLine = executions.findByLineIds(list.stream().flatMap(b -> b.lineIds().stream()).toList())
                .stream().collect(Collectors.toMap(ServiceExecution::bookingServiceId, Function.identity()));
        Map<Integer, String> names = operatorNames();
        List<AssignmentView> result = new ArrayList<>();
        for (BookingSnapshot booking : list) {
            List<ServiceExecution> lines = booking.lineIds().stream().map(byLine::get).filter(e -> e != null).toList();
            if (lines.isEmpty()) continue;
            int operatorId = lines.getFirst().operatorId();
            result.add(new AssignmentView(booking.id(), operatorId, names.getOrDefault(operatorId, ""),
                    statusOf(lines), booking.bay()));
        }
        return result;
    }

    // ================================================================== operario

    @Override
    public List<OperatorServiceView> myServices(LocalDate from, LocalDate to, long operatorUserId) {
        Operator operator = operatorOf(operatorUserId);
        LocalDate first = from == null ? today() : from;
        LocalDate last = to == null ? first : to;
        Map<Long, ServiceExecution> mine = executions.findByOperator(operator.id()).stream()
                .collect(Collectors.toMap(ServiceExecution::bookingServiceId, Function.identity()));
        List<OperatorServiceView> result = new ArrayList<>();
        for (BookingSnapshot booking : bookings.forOperator(first, last)) {
            List<ServiceExecution> lines = booking.lineIds().stream().map(mine::get).filter(e -> e != null).toList();
            if (!lines.isEmpty()) {
                result.add(serviceView(booking, lines));
            }
        }
        result.sort(Comparator.comparing((OperatorServiceView v) -> v.booking().scheduledStart()));
        return result;
    }

    @Override
    public OperatorServiceView start(long bookingId, long operatorUserId) {
        Operator operator = operatorOf(operatorUserId);
        BookingSnapshot booking = requireOperatorBooking(bookingId);
        List<ServiceExecution> lines = myLines(booking, operator);
        // no puede llevar dos lavados a la vez (tr_service_execution_overlap del modelo)
        boolean busy = executions.findByOperator(operator.id()).stream()
                .anyMatch(e -> e.status() == ExecutionStatus.IN_PROGRESS && !booking.lineIds().contains(e.bookingServiceId()));
        if (busy) {
            throw new ConflictException("OPERATOR_BUSY", "Finish the service in progress first");
        }
        Instant now = clock.instant();
        lines.forEach(line -> line.start(now));
        bookings.advance(booking.id(), "IN_PROGRESS");
        executions.saveAll(lines, operatorUserId);
        events.publish(new ExecutionEvent(ExecutionEvent.Type.SERVICE_STARTED, booking.id(), booking.code(),
                booking.ownerUserId(), operatorUserId, booking.scheduledStart(), now));
        return serviceView(booking, lines);
    }

    @Override
    public OperatorServiceView finish(long bookingId, long operatorUserId) {
        Operator operator = operatorOf(operatorUserId);
        BookingSnapshot booking = requireOperatorBooking(bookingId);
        List<ServiceExecution> lines = myLines(booking, operator);
        Instant now = clock.instant();
        lines.forEach(line -> line.finish(now));
        bookings.advance(booking.id(), "COMPLETED");
        executions.saveAll(lines, operatorUserId);
        events.publish(new ExecutionEvent(ExecutionEvent.Type.SERVICE_COMPLETED, booking.id(), booking.code(),
                booking.ownerUserId(), operatorUserId, booking.scheduledStart(), now));
        return serviceView(booking, lines);
    }

    @Override
    public List<RatingView> myRatings(long operatorUserId) {
        Operator operator = operatorOf(operatorUserId);
        Map<Long, ServiceExecution> rated = executions.findRated(operator.id()).stream()
                .collect(Collectors.toMap(ServiceExecution::bookingServiceId, Function.identity()));
        if (rated.isEmpty()) return List.of();
        List<RatingView> result = new ArrayList<>();
        for (BookingSnapshot booking : bookings.forOperator(today().minusDays(RATINGS_DAYS), today())) {
            booking.lineIds().stream().map(rated::get).filter(e -> e != null).findFirst()
                    .ifPresent(e -> result.add(ratingView(booking, e, nameOf(operator))));
        }
        result.sort(Comparator.comparing(RatingView::ratedAt).reversed());
        return result;
    }

    // ================================================================== cliente

    @Override
    public RatingView rate(long bookingId, int rating, String comment, long customerUserId) {
        // booking solo devuelve la reserva si es del cliente que llama (si no, 404)
        BookingSnapshot booking = bookings.forCustomer(bookingId)
                .orElseThrow(() -> new NotFoundException("BOOKING_NOT_FOUND", "Booking not found"));
        if (!"COMPLETED".equals(booking.status())) {
            throw new ConflictException("SERVICE_NOT_COMPLETED", "Only a completed service can be rated");
        }
        List<ServiceExecution> lines = executions.findByLineIds(booking.lineIds());
        if (lines.isEmpty()) {
            throw new ConflictException("SERVICE_NOT_COMPLETED", "This booking has no service to rate");
        }
        Instant now = clock.instant();
        lines.forEach(line -> line.rate(rating, comment, now));
        executions.saveAll(lines, customerUserId);
        String operatorName = operators.findById(lines.getFirst().operatorId()).map(this::nameOf).orElse("");
        return ratingView(booking, lines.getFirst(), operatorName);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RatingView> myGivenRatings(long customerUserId) {
        List<BookingSnapshot> mine = bookings.mine();
        Map<Long, ServiceExecution> byLine = executions.findByLineIds(mine.stream().flatMap(b -> b.lineIds().stream()).toList())
                .stream().filter(e -> e.rating() != null)
                .collect(Collectors.toMap(ServiceExecution::bookingServiceId, Function.identity()));
        List<RatingView> result = new ArrayList<>();
        for (BookingSnapshot booking : mine) {
            booking.lineIds().stream().map(byLine::get).filter(e -> e != null).findFirst()
                    .ifPresent(e -> result.add(ratingView(booking, e, "")));
        }
        return result;
    }

    // ================================================================== apoyo

    private static boolean assignableOrRunning(BookingSnapshot booking) {
        return ASSIGNABLE.contains(booking.status()) || "IN_PROGRESS".equals(booking.status());
    }

    private Operator requireOperator(int operatorId) {
        return operators.findById(operatorId)
                .orElseThrow(() -> new NotFoundException("OPERATOR_NOT_FOUND", "Operator not found"));
    }

    /** El operario que llama; si su cuenta aun no tiene fila, se crea (como el perfil del cliente). */
    private Operator operatorOf(long userId) {
        Optional<Operator> found = operators.findByUserId(userId);
        Operator operator = found.orElseGet(() -> operators.save(Operator.provision(userId, today()), userId));
        if (!operator.active()) {
            throw new ConflictException("OPERATOR_INACTIVE", "The operator is not active");
        }
        return operator;
    }

    private BookingSnapshot requireOperatorBooking(long bookingId) {
        return bookings.forOperator(bookingId)
                .orElseThrow(() -> new NotFoundException("BOOKING_NOT_FOUND", "Booking not found"));
    }

    /** Lineas de la reserva asignadas a este operario; si no tiene ninguna, no es suya (404). */
    private List<ServiceExecution> myLines(BookingSnapshot booking, Operator operator) {
        List<ServiceExecution> lines = executions.findByLineIds(booking.lineIds()).stream()
                .filter(e -> e.operatorId() == operator.id())
                .toList();
        if (lines.isEmpty()) {
            throw new NotFoundException("BOOKING_NOT_FOUND", "This booking is not assigned to you");
        }
        return lines;
    }

    /** Estado de la reserva para el operario: el de la linea mas atrasada. */
    private static ExecutionStatus statusOf(List<ServiceExecution> lines) {
        if (lines.stream().allMatch(e -> e.status() == ExecutionStatus.COMPLETED)) return ExecutionStatus.COMPLETED;
        if (lines.stream().anyMatch(e -> e.status() == ExecutionStatus.IN_PROGRESS)) return ExecutionStatus.IN_PROGRESS;
        return ExecutionStatus.PENDING;
    }

    private static OperatorServiceView serviceView(BookingSnapshot booking, List<ServiceExecution> lines) {
        ServiceExecution first = lines.getFirst();
        return new OperatorServiceView(booking, statusOf(lines), first.startedAt(), first.finishedAt(), first.rating(),
                first.commentVisible() ? first.comment() : null);
    }

    private static RatingView ratingView(BookingSnapshot booking, ServiceExecution execution, String operatorName) {
        return new RatingView(booking.id(), booking.code(), booking.date(), booking.servicesLabel(), booking.vehicle(),
                booking.plate(), execution.rating(), execution.commentVisible() ? execution.comment() : null,
                execution.ratedAt(), operatorName);
    }

    private OperatorView view(Operator operator, UserSnapshot user) {
        List<ServiceExecution> mine = executions.findByOperator(operator.id());
        List<ServiceExecution> rated = mine.stream().filter(e -> e.rating() != null).toList();
        Double average = rated.isEmpty() ? null
                : Math.round(rated.stream().mapToInt(ServiceExecution::rating).average().orElse(0) * 10) / 10.0;
        int completed = (int) mine.stream().filter(e -> e.status() == ExecutionStatus.COMPLETED).count();
        return new OperatorView(operator.id(), operator.userId(), user == null ? "" : user.fullName(),
                user == null ? "" : user.email(), user == null ? "" : user.phone(),
                operator.active() && (user == null || user.active()), operator.hiredOn(),
                operators.availability(operator.id()), completed, average, rated.size());
    }

    private UserSnapshot userOf(Operator operator) {
        return users.operators().stream().filter(u -> u.id() == operator.userId()).findFirst().orElse(null);
    }

    private String nameOf(Operator operator) {
        UserSnapshot user = userOf(operator);
        return user == null ? "" : user.fullName();
    }

    private Map<Integer, String> operatorNames() {
        Map<Long, String> byUser = new HashMap<>();
        users.operators().forEach(u -> byUser.put(u.id(), u.fullName()));
        Map<Integer, String> names = new HashMap<>();
        operators.findAll().forEach(o -> names.put(o.id(), byUser.getOrDefault(o.userId(), "")));
        return names;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    private Instant toInstant(LocalDateTime local) {
        return local.atZone(zone).toInstant().truncatedTo(ChronoUnit.MILLIS);
    }
}
