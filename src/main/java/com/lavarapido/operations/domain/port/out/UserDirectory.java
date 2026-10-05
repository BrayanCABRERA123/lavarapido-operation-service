package com.lavarapido.operations.domain.port.out;

import java.util.List;

/** Cuentas con rol OPERATOR en security-service (nombre y contacto), con el token del admin. */
public interface UserDirectory {

    record UserSnapshot(long id, String fullName, String email, String phone, boolean active) {
    }

    List<UserSnapshot> operators();
}
