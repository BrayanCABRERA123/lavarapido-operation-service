package com.lavarapido.operations.domain.model.inspection;

import com.lavarapido.operations.domain.exception.InvalidValueException;

import java.time.Instant;
import java.util.List;

/**
 * Hallazgo de una fase: la parte del vehiculo, que tan grave es y una nota. Las fotos se guardan
 * aparte (inspection_photo) y aqui solo van sus ids. id es null mientras no se ha guardado.
 */
public record InspectionFinding(Long id, InspectionPhase phase, String area, FindingSeverity severity, String note,
                                List<Long> photoIds, Instant createdAt) {

    public static final int MAX_AREA = 120;
    public static final int MAX_NOTE = 500;

    public InspectionFinding {
        photoIds = photoIds == null ? List.of() : List.copyOf(photoIds);
    }

    static InspectionFinding draft(InspectionPhase phase, String area, FindingSeverity severity, String note,
                                   Instant now) {
        String cleanArea = area == null ? "" : area.trim();
        String cleanNote = note == null ? "" : note.trim();
        if (cleanArea.isEmpty() || cleanArea.length() > MAX_AREA) {
            throw new InvalidValueException("INVALID_FINDING", "The area is required (up to 120 characters)");
        }
        if (cleanNote.length() > MAX_NOTE) {
            throw new InvalidValueException("INVALID_FINDING", "The note has up to 500 characters");
        }
        return new InspectionFinding(null, phase, cleanArea, severity, cleanNote, List.of(), now);
    }
}
