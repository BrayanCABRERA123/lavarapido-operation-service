package com.lavarapido.operations.infrastructure.adapter.out.booking;

import com.lavarapido.operations.domain.exception.ConflictException;
import com.lavarapido.operations.domain.exception.DependencyUnavailableException;
import com.lavarapido.operations.domain.model.BookingSnapshot;
import com.lavarapido.operations.domain.port.out.BookingDirectory;
import com.lavarapido.operations.infrastructure.adapter.out.RestSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Habla con booking-service por REST (ADR-004) reenviando el token de quien llama: booking decide
 * que puede ver cada rol. Si booking no responde, la operacion falla con 503
 * (BOOKING_SERVICE_UNAVAILABLE): no se asigna ni se ejecuta nada sin la reserva real.
 */
@Component
class BookingServiceDirectory implements BookingDirectory {

    private static final Logger log = LoggerFactory.getLogger(BookingServiceDirectory.class);

    private final RestClient client;

    BookingServiceDirectory(@Value("${app.booking-service.base-url}") String baseUrl,
                            @Value("${app.booking-service.timeout:3s}") Duration timeout) {
        this.client = RestSupport.client(baseUrl, timeout);
    }

    @Override
    public Optional<BookingSnapshot> forAdmin(long bookingId) {
        return one(() -> get("/admin/bookings/{id}", bookingId));
    }

    @Override
    public List<BookingSnapshot> forAdmin(LocalDate from, LocalDate to) {
        return many(() -> client.get()
                .uri(uri -> uri.path("/admin/bookings").queryParam("from", from).queryParam("to", to).build())
                .headers(RestSupport::authorize)
                .retrieve()
                .body(BookingJson[].class));
    }

    @Override
    public Optional<BookingSnapshot> forOperator(long bookingId) {
        return one(() -> get("/operator/bookings/{id}", bookingId));
    }

    @Override
    public List<BookingSnapshot> forOperator(LocalDate from, LocalDate to) {
        return many(() -> client.get()
                .uri(uri -> uri.path("/operator/bookings").queryParam("date", from).queryParam("to", to).build())
                .headers(RestSupport::authorize)
                .retrieve()
                .body(BookingJson[].class));
    }

    @Override
    public Optional<BookingSnapshot> forCustomer(long bookingId) {
        return one(() -> get("/bookings/{id}", bookingId));
    }

    @Override
    public List<BookingSnapshot> mine() {
        return many(() -> client.get()
                .uri("/bookings/me")
                .headers(RestSupport::authorize)
                .retrieve()
                .body(BookingJson[].class));
    }

    @Override
    public void advance(long bookingId, String status) {
        try {
            client.patch()
                    .uri("/operator/bookings/{id}/status", bookingId)
                    .headers(RestSupport::authorize)
                    .body(Map.of("status", status))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            // la reserva no esta en el estado que permite ese paso (cancelada, ya terminada...)
            if (e.getStatusCode() == HttpStatus.CONFLICT || e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                throw new ConflictException("BOOKING_STATE_CONFLICT", "The booking cannot move to " + status);
            }
            throw unavailable(e);
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    private BookingJson get(String path, long id) {
        return client.get().uri(path, id).headers(RestSupport::authorize).retrieve().body(BookingJson.class);
    }

    private Optional<BookingSnapshot> one(Supplier<BookingJson> call) {
        try {
            return Optional.ofNullable(call.get()).map(BookingJson::toSnapshot);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw unavailable(e);
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    private List<BookingSnapshot> many(Supplier<BookingJson[]> call) {
        try {
            BookingJson[] found = call.get();
            return found == null ? List.of() : Arrays.stream(found).map(BookingJson::toSnapshot).toList();
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    private static DependencyUnavailableException unavailable(RestClientException e) {
        log.warn("booking-service did not answer: {}", e.getMessage());
        return new DependencyUnavailableException("BOOKING_SERVICE_UNAVAILABLE", "booking-service is not available");
    }

    // forma del JSON de booking-service (solo los campos que se usan)
    record BookingJson(long id, String code, String status, LocalDate date, LocalTime startTime, LocalTime endTime,
                       Instant scheduledStart, Instant scheduledEnd, Long ownerUserId, VehicleJson vehicle,
                       BigDecimal total, List<LineJson> services) {

        BookingSnapshot toSnapshot() {
            String vehicleName = vehicle == null ? "" : vehicle.label();
            List<BookingSnapshot.Line> lines = services == null ? List.of() : services.stream()
                    .filter(line -> line.lineId() != null)
                    .map(line -> new BookingSnapshot.Line(line.lineId(), line.name()))
                    .toList();
            return new BookingSnapshot(id, code, status, date, startTime, endTime, scheduledStart, scheduledEnd,
                    ownerUserId, vehicleName, vehicle == null ? "" : nullToEmpty(vehicle.licensePlateFormatted()),
                    total == null ? BigDecimal.ZERO : total, lines);
        }
    }

    record VehicleJson(String brand, String model, String vehicleTypeName, String licensePlateFormatted) {

        String label() {
            String name = (nullToEmpty(brand) + " " + nullToEmpty(model)).trim();
            return name.isEmpty() ? nullToEmpty(vehicleTypeName) : name;
        }
    }

    record LineJson(Long lineId, String name) {
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
