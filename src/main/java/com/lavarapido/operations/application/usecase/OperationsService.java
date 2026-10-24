package com.lavarapido.operations.application.usecase;

import com.lavarapido.operations.domain.event.ExecutionEvent;
import com.lavarapido.operations.domain.exception.ConflictException;
import com.lavarapido.operations.domain.exception.InvalidValueException;
import com.lavarapido.operations.domain.exception.NotFoundException;
import com.lavarapido.operations.domain.model.Absence;
import com.lavarapido.operations.domain.model.BookingSnapshot;
import com.lavarapido.operations.domain.model.ExecutionStatus;
import com.lavarapido.operations.domain.model.OperatorWeekMetrics;
import com.lavarapido.operations.domain.model.Operator;
import com.lavarapido.operations.domain.model.ServiceExecution;
import com.lavarapido.operations.domain.model.TeamSummary;
import com.lavarapido.operations.domain.model.WeeklyAvailability;
import com.lavarapido.operations.domain.port.in.OperationsUseCase;
import com.lavarapido.operations.domain.port.out.BookingDirectory;
import com.lavarapido.operations.domain.port.out.DomainEventPublisher;
import com.lavarapido.operations.domain.port.out.ExecutionRepository;
import com.lavarapido.operations.domain.port.out.OperatorRepository;
import com.lavarapido.operations.domain.port.out.UserDirectory;
import com.lavarapido.operations.domain.port.out.UserDirectory.UserSnapshot;
import com.lavarapido.operations.domain.service.AssignmentPolicy;
import com.lavarapido.operations.domain.service.AutoAssignmentPolicy;
import com.lavarapido.operations.domain.service.OperatorMetricsCalculator;
import com.lavarapido.operations.domain.service.StartPolicy;
import com.lavarapido.operations.domain.service.TeamSummaryCalculator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
    // la asignación automática la hace el sistema, no un usuario
    private static final long SYSTEM_ACTOR = 0L;
    private static final int MIN_SLOT_MINUTES = 15;
    private static final int MAX_SLOT_MINUTES = 600;
    // historial de calificaciones del operario: lo que booking deja listar por consulta
    private static final int RATINGS_DAYS = 60;

    private final OperatorRepository operators;
    private final ExecutionRepository executions;
    private final BookingDirectory bookings;
    private final UserDirectory users;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final ZoneId zone;
    // A-12: hasta cuánto después de la hora de fin se puede iniciar un servicio (StartPolicy)
    private final Duration startLateLimit;

    public OperationsService(OperatorRepository operators, ExecutionRepository executions, BookingDirectory bookings,
                             UserDirectory users, DomainEventPublisher events, Clock clock, ZoneId zone,
                             @Value("${app.start.late-limit:PT4H}") Duration startLateLimit) {
        this.operators = operators;
        this.executions = executions;
        this.bookings = bookings;
        this.users = users;
        this.events = events;
        this.clock = clock;
        this.zone = zone;
        this.startLateLimit = startLateLimit;
    }

    // ================================================================== admin

    @Override
    public OperatorWeekMetrics metrics(int operatorId, LocalDate weekOf) {
        Operator operator = requireOperator(operatorId);
        // semana de lunes a domingo en la hora del lavadero; se trae también la anterior para la tendencia
        LocalDate weekStart = (weekOf == null ? today() : weekOf).with(DayOfWeek.MONDAY);
        List<BookingSnapshot> bookingsOfTwoWeeks = bookings.forAdmin(weekStart.minusDays(7), weekStart.plusDays(6));
        return OperatorMetricsCalculator.compute(weekStart, bookingsOfTwoWeeks, executions.findByOperator(operator.id()),
                operators.availability(operator.id()));
    }

    @Override
    public TeamSummary teamSummary(LocalDate weekOf) {
        LocalDate today = today();
        LocalDate weekStart = (weekOf == null ? today : weekOf).with(DayOfWeek.MONDAY);
        List<BookingSnapshot> bookingsOfTwoWeeks = bookings.forAdmin(weekStart.minusDays(7), weekStart.plusDays(6));
        Instant dayStart = toInstant(today.atStartOfDay());
        Instant dayEnd = toInstant(today.plusDays(1).atStartOfDay());
        List<TeamSummaryCalculator.Member> members = new ArrayList<>();
        for (OperatorView view : operators()) {
            int id = view.operatorId();
            boolean onShift = view.week().stream().anyMatch(shift -> shift.dayOfWeek() == today.getDayOfWeek().getValue());
            boolean absent = !operators.absences(id, dayStart, dayEnd).isEmpty();
            OperatorWeekMetrics week = OperatorMetricsCalculator.compute(weekStart, bookingsOfTwoWeeks,
                    executions.findByOperator(id), view.week());
            members.add(new TeamSummaryCalculator.Member(view.active(), onShift, absent, week,
                    view.averageRating(), view.ratingsCount()));
        }
        return TeamSummaryCalculator.compute(weekStart, members);
    }

    @Override
    public List<OperatorView> operators() {
        Map<Long, Operator> byUser = operators.findAll().stream()
                .collect(Collectors.toMap(Operator::userId, Function.identity()));
        List<OperatorView> result = new ArrayList<>();
        for (UserSnapshot user : users.operators()) {
            // una cuenta con rol OPERATOR que aun no tiene fila de operario la recibe aqui
            Operator operator = byUser.containsKey(user.id()) ? byUser.get(user.id())
                    : operators.provision(user.id(), today(), SYSTEM_ACTOR);
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
        // una ausencia se registra de hoy en adelante: los días que ya pasaron no se reescriben
        if (startsAt.toLocalDate().isBefore(today())) {
            throw new InvalidValueException("ABSENCE_IN_PAST", "An absence cannot start before today");
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
    public AssignmentView assign(long bookingId, int operatorId, long adminId, String notes) {
        BookingSnapshot booking = bookings.forAdmin(bookingId)
                .orElseThrow(() -> new NotFoundException("BOOKING_NOT_FOUND", "Booking not found"));
        if (!ASSIGNABLE.contains(booking.status())) {
            throw new ConflictException("BOOKING_NOT_ASSIGNABLE", "Only a booking that has not started can be assigned");
        }
        // una reserva cuya hora ya pasó no se asigna (quedaría un "nuevo servicio" de días atrás)
        if (!booking.scheduledEnd().isAfter(clock.instant())) {
            throw new ConflictException("BOOKING_IN_PAST", "A booking whose time has already passed cannot be assigned");
        }
        Operator operator = requireOperator(operatorId);
        // A-13: igual que la automática, una cuenta desactivada en security no recibe servicios
        if (!accountActive(operator)) {
            throw new ConflictException("OPERATOR_ACCOUNT_DISABLED", "The operator account is disabled");
        }

        // OPS-11: lo que viene de otros servicios (las reservas del día y el nombre del operario) se pide
        // antes del candado, para no retenerlo mientras booking o security responden
        List<BookingSnapshot> sameDay = otherBookingsSameDay(booking);
        String operatorName = nameOf(operator);

        // con las asignaciones bloqueadas, las ejecuciones del operario se leen ya con la automatica hecha
        executions.lockAssignments();
        AssignmentPolicy.check(operator, operators.availability(operatorId),
                operators.absences(operatorId, booking.scheduledStart(), booking.scheduledEnd()), booking,
                busyWith(sameDay, executionsOf(sameDay), operatorId));

        // OPS-03: el estado se vuelve a leer ya con el candado y con las líneas bloqueadas: si el operario
        // la inició mientras tanto, ya no se reasigna (y su inicio no se pierde)
        Map<Long, ServiceExecution> existing = executions.lockByLineIds(booking.lineIds()).stream()
                .collect(Collectors.toMap(ServiceExecution::bookingServiceId, Function.identity()));
        if (existing.values().stream().anyMatch(execution -> execution.status() != ExecutionStatus.PENDING)) {
            throw new ConflictException("BOOKING_NOT_ASSIGNABLE", "Only a booking that has not started can be assigned");
        }
        List<ServiceExecution> toSave = new ArrayList<>();
        for (long lineId : booking.lineIds()) {
            ServiceExecution execution = existing.get(lineId);
            if (execution == null) {
                toSave.add(ServiceExecution.assign(lineId, operatorId, notes));
            } else {
                execution.reassign(operatorId, notes);
                toSave.add(execution);
            }
        }
        executions.saveAll(toSave, adminId);

        events.publish(new ExecutionEvent(ExecutionEvent.Type.OPERATOR_ASSIGNED, booking.id(), booking.code(),
                booking.ownerUserId(), operator.userId(), booking.scheduledStart(), clock.instant()));
        return new AssignmentView(booking.id(), operatorId, operatorName, ExecutionStatus.PENDING, booking.bay(),
                toSave.isEmpty() ? null : toSave.getFirst().assignmentNotes());
    }

    @Override
    public Optional<AssignmentView> autoAssign(long bookingId, Long customerUserId, Integer preferredOperatorId) {
        Optional<BookingSnapshot> found = bookings.forService(bookingId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        BookingSnapshot booking = found.get();
        if (!ASSIGNABLE.contains(booking.status()) || !booking.scheduledEnd().isAfter(clock.instant())) {
            return Optional.empty();
        }
        // OPS-11: las reservas del día (booking) y las cuentas de los operarios (security) se piden antes
        // del candado: si uno de esos servicios tarda, la asignación manual no espera hasta ASSIGNMENT_BUSY
        List<BookingSnapshot> sameDay = bookings.forService(booking.date(), booking.date()).stream()
                .filter(other -> other.id() != booking.id() && assignableOrRunning(other))
                .toList();
        List<Operator> team = operators.findAll();
        Map<Integer, Boolean> activeAccounts = new HashMap<>();
        team.forEach(operator -> activeAccounts.put(operator.id(), accountActive(operator)));

        // con las asignaciones bloqueadas: una asignacion manual al mismo tiempo se ve antes de elegir
        executions.lockAssignments();
        List<ServiceExecution> current = executions.lockByLineIds(booking.lineIds());
        if (current.stream().anyMatch(e -> e.status() != ExecutionStatus.PENDING)) {
            return Optional.empty();
        }
        List<ServiceExecution> sameDayExecutions = executionsOf(sameDay);
        // E-12: una reprogramación conserva al operario solo si todavía puede a la hora nueva (turno,
        // ausencias, cruces y cuenta activa); si no, pasa a uno libre. Sin nadie libre queda sin operario.
        Integer previous = current.isEmpty() ? null : current.getFirst().operatorId();
        if (previous != null && stillFits(team, previous, booking, busyWith(sameDay, sameDayExecutions, previous),
                activeAccounts)) {
            return Optional.empty();
        }
        List<AutoAssignmentPolicy.Candidate> free = new ArrayList<>();
        for (Operator operator : team) {
            int id = operator.id();
            if (previous != null && id == previous) continue;
            List<BookingSnapshot> busy = busyWith(sameDay, sameDayExecutions, id);
            String reason = AssignmentPolicy.blockingReason(operator, operators.availability(id),
                    operators.absences(id, booking.scheduledStart(), booking.scheduledEnd()), booking, busy);
            if (reason == null && activeAccounts.getOrDefault(id, false)) {
                free.add(new AutoAssignmentPolicy.Candidate(id, busy.size()));
            }
        }
        Optional<Integer> chosen = AutoAssignmentPolicy.choose(free, preferredOperatorId);
        if (chosen.isEmpty()) {
            if (previous != null) {
                unassign(booking, current, previous, team, customerUserId);
            }
            return Optional.empty();
        }
        Operator operator = requireOperator(chosen.get());
        if (current.isEmpty()) {
            executions.saveAll(booking.lineIds().stream().map(line -> ServiceExecution.assign(line, operator.id())).toList(),
                    SYSTEM_ACTOR);
        } else {
            current.forEach(execution -> execution.reassign(operator.id()));
            executions.saveAll(current, SYSTEM_ACTOR);
        }
        events.publish(new ExecutionEvent(ExecutionEvent.Type.OPERATOR_ASSIGNED, booking.id(), booking.code(),
                customerUserId, operator.userId(), booking.scheduledStart(), clock.instant()));
        return Optional.of(new AssignmentView(booking.id(), operator.id(), "", ExecutionStatus.PENDING, booking.bay(), null));
    }

    /**
     * E-12: el operario que tenía la reserva ya no puede a la hora nueva y nadie más está libre. En vez
     * de dejársela a quien no la puede atender, la reserva queda sin operario (sale en "Sin asignar") y
     * notification-service avisa al operario y a los administradores (OperatorUnassigned).
     */
    private void unassign(BookingSnapshot booking, List<ServiceExecution> current, int previous, List<Operator> team,
                          Long customerUserId) {
        executions.deletePending(current);
        Long previousUserId = team.stream().filter(operator -> operator.id() == previous).map(Operator::userId)
                .findFirst().orElse(null);
        events.publish(new ExecutionEvent(ExecutionEvent.Type.OPERATOR_UNASSIGNED, booking.id(), booking.code(),
                customerUserId, previousUserId, booking.scheduledStart(), clock.instant()));
    }

    /**
     * E-12: el operario puede tomar la reserva en su hora (actual) y su cuenta sigue activa. team y
     * activeAccounts se leyeron antes del candado (OPS-11).
     */
    private boolean stillFits(List<Operator> team, int operatorId, BookingSnapshot booking, List<BookingSnapshot> busy,
                              Map<Integer, Boolean> activeAccounts) {
        Optional<Operator> found = team.stream().filter(operator -> operator.id() == operatorId).findFirst();
        if (found.isEmpty()) {
            return false;
        }
        Operator operator = found.get();
        return AssignmentPolicy.blockingReason(operator, operators.availability(operatorId),
                operators.absences(operatorId, booking.scheduledStart(), booking.scheduledEnd()), booking, busy) == null
                && activeAccounts.getOrDefault(operatorId, false);
    }

    /**
     * La cuenta en security sigue activa. A-13: si security no responde, falla cerrado (503
     * SECURITY_SERVICE_UNAVAILABLE) en vez de suponer que sí: una cuenta desactivada no recibe servicios.
     * La manual se reintenta con un clic y la automática la reintenta RabbitMQ (OPS-04).
     */
    private boolean accountActive(Operator operator) {
        return users.accountActive(operator.userId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AvailableOperatorView> availableOperators(LocalDate date, LocalTime time, int minutes) {
        if (date == null || time == null || minutes < MIN_SLOT_MINUTES || minutes > MAX_SLOT_MINUTES) {
            throw new InvalidValueException("INVALID_SLOT", "Give the date, the time and a duration of 15 to 600 minutes");
        }
        Instant start = toInstant(date.atTime(time));
        Instant end = toInstant(date.atTime(time).plusMinutes(minutes));
        if (!end.isAfter(clock.instant())) {
            return List.of();
        }
        // la reserva que el cliente está por hacer (todavía no existe: id 0)
        BookingSnapshot slot = new BookingSnapshot(0, null, "CONFIRMED", date, time, time.plusMinutes(minutes),
                start, end, null, "", "", null, List.of(), null);
        List<BookingSnapshot> sameDay = bookings.forService(date, date).stream()
                .filter(OperationsService::assignableOrRunning).toList();
        List<ServiceExecution> sameDayExecutions = executionsOf(sameDay);
        List<AvailableOperatorView> result = new ArrayList<>();
        for (Operator operator : operators.findAll()) {
            int id = operator.id();
            String reason = AssignmentPolicy.blockingReason(operator, operators.availability(id),
                    operators.absences(id, start, end), slot, busyWith(sameDay, sameDayExecutions, id));
            if (reason != null) {
                continue;
            }
            Optional<UserDirectory.ContactSnapshot> contact = users.contactOf(operator.userId());
            if (contact.isPresent() && !contact.get().active()) {
                continue;
            }
            List<ServiceExecution> rated = executions.findRated(id);
            Double average = rated.isEmpty() ? null
                    : Math.round(rated.stream().mapToInt(ServiceExecution::rating).average().orElse(0) * 10) / 10.0;
            result.add(new AvailableOperatorView(id, contact.map(UserDirectory.ContactSnapshot::firstName).orElse(""),
                    average, rated.size()));
        }
        result.sort(Comparator.comparing((AvailableOperatorView v) -> v.averageRating() == null ? 0 : -v.averageRating())
                .thenComparing(AvailableOperatorView::firstName, String.CASE_INSENSITIVE_ORDER));
        return result;
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
            Operator candidate = requireOperator(id);
            String reason = AssignmentPolicy.blockingReason(candidate, operators.availability(id),
                    operators.absences(id, booking.scheduledStart(), booking.scheduledEnd()), booking,
                    busyWith(sameDay, sameDayExecutions, id));
            // A-13: la lista del admin también avisa la cuenta desactivada
            if (reason == null && !accountActive(candidate)) {
                reason = "OPERATOR_ACCOUNT_DISABLED";
            }
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
            // una reserva cancelada ya no ocupa al operario: no se lista como asignación pendiente
            if ("CANCELLED".equals(booking.status())) continue;
            List<ServiceExecution> lines = booking.lineIds().stream().map(byLine::get).filter(e -> e != null).toList();
            if (lines.isEmpty()) continue;
            int operatorId = lines.getFirst().operatorId();
            result.add(new AssignmentView(booking.id(), operatorId, names.getOrDefault(operatorId, ""),
                    statusOf(lines), booking.bay(), lines.getFirst().assignmentNotes()));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public UnassignedView unassigned(LocalDate from, LocalDate to) {
        LocalDate first = from == null ? today() : from;
        LocalDate last = to == null ? first : to;
        Instant now = clock.instant();
        List<BookingSnapshot> open = bookings.forAdmin(first, last).stream()
                .filter(booking -> ASSIGNABLE.contains(booking.status()) && booking.scheduledEnd().isAfter(now))
                .toList();
        Set<Long> assignedLines = executionsOf(open).stream()
                .map(ServiceExecution::bookingServiceId).collect(Collectors.toSet());
        return new UnassignedView(open.stream()
                .filter(booking -> booking.lineIds().stream().noneMatch(assignedLines::contains))
                .sorted(Comparator.comparing(BookingSnapshot::scheduledStart))
                .map(BookingSnapshot::id)
                .toList());
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
        Map<Long, String> clientNames = new HashMap<>();
        for (BookingSnapshot booking : bookings.forOperator(first, last)) {
            List<ServiceExecution> lines = booking.lineIds().stream().map(mine::get).filter(e -> e != null).toList();
            if (!lines.isEmpty()) {
                result.add(serviceView(booking, lines, clientNameOf(booking, clientNames)));
            }
        }
        result.sort(Comparator.comparing((OperatorServiceView v) -> v.booking().scheduledStart()));
        return result;
    }

    @Override
    public OperatorServiceView start(long bookingId, long operatorUserId) {
        Operator operator = operatorOf(operatorUserId);
        BookingSnapshot booking = requireOperatorBooking(bookingId);
        // OPS-03: con la fila del operario bloqueada, dos inicios suyos al mismo tiempo van uno detrás del
        // otro y el segundo ya ve el primero en curso
        operators.lock(operator.id());
        List<ServiceExecution> lines = myLines(booking, operator);
        Instant now = clock.instant();
        // no se inicia una reserva de otro día u otra hora: desde 15 minutos antes y hasta el límite (A-12)
        StartPolicy.check(booking, now, startLateLimit);
        // no puede llevar dos lavados a la vez (lo garantiza el bloqueo de arriba)
        boolean busy = executions.findByOperator(operator.id()).stream()
                .anyMatch(e -> e.status() == ExecutionStatus.IN_PROGRESS && !booking.lineIds().contains(e.bookingServiceId()));
        if (busy) {
            throw new ConflictException("OPERATOR_BUSY", "Finish the service in progress first");
        }
        lines.forEach(line -> line.start(now));
        // OPS-01: primero la ejecución (escrita en la base) y después la reserva. Si booking falla, la
        // transacción se deshace entera y el operario puede volver a intentarlo sin quedar bloqueado
        executions.saveAll(lines, operatorUserId);
        executions.flush();
        bookings.advance(booking.id(), "IN_PROGRESS");
        events.publish(new ExecutionEvent(ExecutionEvent.Type.SERVICE_STARTED, booking.id(), booking.code(),
                booking.ownerUserId(), operatorUserId, booking.scheduledStart(), now));
        return serviceView(booking, lines, clientNameOf(booking, new HashMap<>()));
    }

    @Override
    public OperatorServiceView finish(long bookingId, long operatorUserId) {
        Operator operator = operatorOf(operatorUserId);
        BookingSnapshot booking = requireOperatorBooking(bookingId);
        List<ServiceExecution> lines = myLines(booking, operator);
        Instant now = clock.instant();
        lines.forEach(line -> line.finish(now));
        // OPS-01: igual que al iniciar, la ejecución va antes que la reserva
        executions.saveAll(lines, operatorUserId);
        executions.flush();
        bookings.advance(booking.id(), "COMPLETED");
        events.publish(new ExecutionEvent(ExecutionEvent.Type.SERVICE_COMPLETED, booking.id(), booking.code(),
                booking.ownerUserId(), operatorUserId, booking.scheduledStart(), now));
        return serviceView(booking, lines, clientNameOf(booking, new HashMap<>()));
    }

    @Override
    public List<RatingView> myRatings(long operatorUserId) {
        Operator operator = operatorOf(operatorUserId);
        Map<Long, ServiceExecution> rated = executions.findRated(operator.id()).stream()
                .collect(Collectors.toMap(ServiceExecution::bookingServiceId, Function.identity()));
        if (rated.isEmpty()) return List.of();
        List<RatingView> result = new ArrayList<>();
        // cada nombre se pide una sola vez a security-service (un cliente puede tener varias reservas)
        Map<Long, String> clientNames = new HashMap<>();
        String operatorName = users.firstNameOf(operator.userId());
        for (BookingSnapshot booking : bookings.forOperator(today().minusDays(RATINGS_DAYS), today())) {
            booking.lineIds().stream().map(rated::get).filter(e -> e != null).findFirst()
                    .ifPresent(e -> result.add(ratingView(booking, e, operatorName, clientNameOf(booking, clientNames))));
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
        // OPS-03: bloqueadas, así dos envíos de la misma calificación no la guardan dos veces
        List<ServiceExecution> lines = executions.lockByLineIds(booking.lineIds());
        if (lines.isEmpty()) {
            throw new ConflictException("SERVICE_NOT_COMPLETED", "This booking has no service to rate");
        }
        Instant now = clock.instant();
        lines.forEach(line -> line.rate(rating, comment, now));
        executions.saveAll(lines, customerUserId);
        String operatorName = operatorFirstName(lines.getFirst().operatorId(), new HashMap<>());
        return ratingView(booking, lines.getFirst(), operatorName, users.firstNameOf(customerUserId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RatingView> myGivenRatings(long customerUserId) {
        List<BookingSnapshot> mine = bookings.mine();
        Map<Long, ServiceExecution> byLine = executions.findByLineIds(mine.stream().flatMap(b -> b.lineIds().stream()).toList())
                .stream().filter(e -> e.rating() != null)
                .collect(Collectors.toMap(ServiceExecution::bookingServiceId, Function.identity()));
        List<RatingView> result = new ArrayList<>();
        // el cliente ve el primer nombre de quien lavó su vehículo
        Map<Integer, String> operatorNames = new HashMap<>();
        for (BookingSnapshot booking : mine) {
            booking.lineIds().stream().map(byLine::get).filter(e -> e != null).findFirst()
                    .ifPresent(e -> result.add(ratingView(booking, e, operatorFirstName(e.operatorId(), operatorNames), "")));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingOperatorView> myBookingOperators() {
        // mine() consulta el booking-service con el token del cliente: solo trae sus reservas
        List<BookingSnapshot> mine = bookings.mine();
        Map<Long, ServiceExecution> byLine = executions.findByLineIds(mine.stream().flatMap(b -> b.lineIds().stream()).toList())
                .stream().collect(Collectors.toMap(ServiceExecution::bookingServiceId, Function.identity(), (a, b) -> a));
        Map<Integer, String> operatorNames = new HashMap<>();
        List<BookingOperatorView> result = new ArrayList<>();
        for (BookingSnapshot booking : mine) {
            booking.lineIds().stream().map(byLine::get).filter(e -> e != null).findFirst()
                    .ifPresent(e -> result.add(new BookingOperatorView(booking.id(),
                            operatorFirstName(e.operatorId(), operatorNames))));
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

    /**
     * El operario que llama; si su cuenta aun no tiene fila, se crea (como el perfil del cliente).
     * Antes se confirma con security-service que la cuenta existe: un token de prueba o de una cuenta
     * borrada no deja filas de operario huerfanas.
     */
    private Operator operatorOf(long userId) {
        Optional<Operator> found = operators.findByUserId(userId);
        if (found.isEmpty() && users.accountMissing(userId)) {
            throw new NotFoundException("OPERATOR_NOT_FOUND", "There is no operator account for this user");
        }
        Operator operator = found.orElseGet(() -> operators.provision(userId, today(), userId));
        if (!operator.active()) {
            throw new ConflictException("OPERATOR_INACTIVE", "The operator is not active");
        }
        return operator;
    }

    private BookingSnapshot requireOperatorBooking(long bookingId) {
        return bookings.forOperator(bookingId)
                .orElseThrow(() -> new NotFoundException("BOOKING_NOT_FOUND", "Booking not found"));
    }

    /**
     * Lineas de la reserva asignadas a este operario; si no tiene ninguna, no es suya (404). Se leen
     * bloqueadas (OPS-03): un cambio del admin a la misma reserva espera a que esto termine.
     */
    private List<ServiceExecution> myLines(BookingSnapshot booking, Operator operator) {
        List<ServiceExecution> lines = executions.lockByLineIds(booking.lineIds()).stream()
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

    private OperatorServiceView serviceView(BookingSnapshot booking, List<ServiceExecution> lines, String clientName) {
        ServiceExecution first = lines.getFirst();
        return new OperatorServiceView(booking, statusOf(lines), first.startedAt(), first.finishedAt(), first.rating(),
                first.commentVisible() ? first.comment() : null, StartPolicy.startableAt(booking), clientName,
                StartPolicy.startableUntil(booking, startLateLimit), first.assignmentNotes());
    }

    private static RatingView ratingView(BookingSnapshot booking, ServiceExecution execution, String operatorName,
                                         String clientName) {
        return new RatingView(booking.id(), booking.code(), booking.date(), booking.servicesLabel(), booking.vehicle(),
                booking.plate(), execution.rating(), execution.commentVisible() ? execution.comment() : null,
                execution.ratedAt(), operatorName, clientName);
    }

    private String clientNameOf(BookingSnapshot booking, Map<Long, String> cache) {
        Long owner = booking.ownerUserId();
        return owner == null ? "" : cache.computeIfAbsent(owner, users::firstNameOf);
    }

    private String operatorFirstName(int operatorId, Map<Integer, String> cache) {
        return cache.computeIfAbsent(operatorId, id -> operators.findById(id)
                .map(operator -> users.firstNameOf(operator.userId())).orElse(""));
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
