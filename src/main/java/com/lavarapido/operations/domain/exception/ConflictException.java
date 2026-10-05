package com.lavarapido.operations.domain.exception;

/** La peticion es valida pero choca con el estado actual (409). */
public class ConflictException extends DomainException {

    public ConflictException(String code, String message) {
        super(code, message);
    }
}
