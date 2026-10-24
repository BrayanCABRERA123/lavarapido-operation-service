package com.lavarapido.operations.domain.port.out;

import java.util.List;
import java.util.Optional;

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

    /** Primer nombre y si la cuenta está activa en security-service (contacto interno, llave interna). */
    record ContactSnapshot(String firstName, boolean active) {
    }

    /**
     * Contacto de la cuenta sin usuario detrás, para mostrar (primer nombre, operarios libres que ve el
     * cliente, RF-010). Vacío si no se pudo leer. Para decidir si se asigna se usa accountActive.
     */
    Optional<ContactSnapshot> contactOf(long userId);

    /**
     * Si la cuenta puede recibir servicios (asignación manual y automática, A-13): true si está activa,
     * false si está desactivada o no existe. Sin respuesta de security (o sin llave interna) lanza
     * DependencyUnavailableException (503 SECURITY_SERVICE_UNAVAILABLE): no se asigna a ciegas.
     */
    boolean accountActive(long userId);

    /**
     * true solo cuando security-service confirma que la cuenta no existe (404). Sin llave interna o
     * sin respuesta devuelve false, para no dejar por fuera a un operario real por una caida.
     */
    boolean accountMissing(long userId);
}
