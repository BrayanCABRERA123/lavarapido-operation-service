package com.lavarapido.operations.domain.port.out;

import java.util.List;

/** Cuentas con rol OPERATOR en security-service (nombre y contacto), con el token del admin. */
public interface UserDirectory {

    record UserSnapshot(long id, String fullName, String email, String phone, boolean active) {
    }

    List<UserSnapshot> operators();

    /**
     * Primer nombre de cualquier cuenta (cliente u operario) para mostrarlo en las calificaciones.
     * Se lee del contacto interno de security-service con la llave interna, así funciona con el
     * token de cualquier rol. Vacío si no se pudo leer (la pantalla muestra la placa).
     */
    String firstNameOf(long userId);
}
