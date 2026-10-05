package com.lavarapido.operations.domain.exception;

/**
 * Error de negocio con un code estable que el frontend traduce (API_ERRORS.<code>).
 * El mensaje es para el log y para quien lee la API, nunca para mostrarlo tal cual.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
