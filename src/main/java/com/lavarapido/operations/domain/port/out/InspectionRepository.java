package com.lavarapido.operations.domain.port.out;

import com.lavarapido.operations.domain.model.inspection.InspectionReport;

import java.util.Optional;

/** Informes de inspeccion y sus fotos (esquema execution, migracion 033). */
public interface InspectionRepository {

    /** Foto guardada: el binario y a que informe pertenece (para no servir fotos de otro informe). */
    record StoredPhoto(long id, long reportId, String contentType, byte[] content) {
    }

    Optional<InspectionReport> findByBooking(long bookingId);

    Optional<InspectionReport> findByPublicToken(String publicToken);

    /** Guarda el informe, sus fases y los hallazgos nuevos (id null); devuelve el informe como quedo. */
    InspectionReport save(InspectionReport report, long actor);

    /** Borrado logico del hallazgo; sus fotos dejan de servirse. */
    void removeFinding(long findingId, long actor);

    /** Guarda una foto de un hallazgo y devuelve su id. */
    long addPhoto(long findingId, String contentType, byte[] content, long actor);

    Optional<StoredPhoto> photo(long photoId);
}
