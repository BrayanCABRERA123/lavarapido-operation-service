package com.lavarapido.operations.infrastructure.adapter.in.web;

import com.lavarapido.operations.domain.exception.ConflictException;
import com.lavarapido.operations.domain.exception.DependencyUnavailableException;
import com.lavarapido.operations.domain.exception.DomainException;
import com.lavarapido.operations.domain.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Todos los errores salen como application/problem+json con un code fijo que el frontend
 * traduce (API_ERRORS.<code>). Los detalles internos nunca llegan al cliente.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    ProblemDetail handleDomain(DomainException exception) {
        return problem(statusOf(exception), exception.code(), exception.getMessage());
    }

    /**
     * Los indices unicos y los triggers de la base son la ultima red (por ejemplo, dos ausencias
     * cruzadas del mismo operario, tr_operator_absence_overlap). Si saltan es 409, no 500.
     */
    @ExceptionHandler({DataIntegrityViolationException.class, DataAccessException.class})
    ProblemDetail handleDataConflict(DataAccessException exception) {
        log.warn("Database rejected the change: {}", exception.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "CONFLICT", "The data conflicts with an existing record");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Parameter " + exception.getName() + " is invalid");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception) {
        log.error("Unexpected error", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The request has invalid fields");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /** Los errores de Spring MVC (JSON mal formado, metodo incorrecto) tambien llevan code. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getProperties() == null) {
            problem.setProperty("code", HttpStatus.valueOf(statusCode.value()).name());
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static HttpStatus statusOf(DomainException exception) {
        return switch (exception) {
            // no existe, o es de otro cliente: 404 en los dos casos para no revelar que existe
            case NotFoundException ignored -> HttpStatus.NOT_FOUND;
            case ConflictException ignored -> HttpStatus.CONFLICT;
            case DependencyUnavailableException ignored -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        return problem;
    }
}
