package com.lavarapido.operations.domain.exception;

/** Lo pedido no existe, o no es de quien lo pide (404): asi no se revela que existe. */
public class NotFoundException extends DomainException {

    public NotFoundException(String code, String message) {
        super(code, message);
    }
}
