package com.lavarapido.operations.domain.port.in;

import com.lavarapido.operations.domain.model.inspection.FindingSeverity;
import com.lavarapido.operations.domain.model.inspection.InspectionPhase;
import com.lavarapido.operations.domain.model.inspection.InspectionPhaseStatus;
import com.lavarapido.operations.domain.model.inspection.InspectionReport;
import com.lavarapido.operations.domain.port.out.InspectionRepository.StoredPhoto;

import java.util.List;

/** Informe de inspeccion del vehiculo (RF-027): lo llena el admin y el cliente lo ve por un enlace publico. */
public interface InspectionUseCase {

    /** Si la reserva admite informe, si se puede editar y el informe (null si aun no se inicio). */
    record InspectionView(boolean available, boolean editable, InspectionReport report) {
    }

    /** Foto recibida en la peticion. */
    record PhotoUpload(String contentType, byte[] content) {
    }

    InspectionView forBooking(long bookingId);

    InspectionView start(long bookingId, long adminId);

    InspectionView setPhaseStatus(long bookingId, InspectionPhase phase, InspectionPhaseStatus status, long adminId);

    InspectionView addFinding(long bookingId, InspectionPhase phase, String area, FindingSeverity severity, String note,
                              List<PhotoUpload> photos, long adminId);

    InspectionView removeFinding(long bookingId, long findingId, long adminId);

    InspectionView publish(long bookingId, long adminId);

    /** Avisa al cliente (bandeja, push y correo) con el resumen y el enlace; exige el informe publicado. */
    void share(long bookingId, long adminId);

    StoredPhoto photoForAdmin(long bookingId, long photoId);

    /** Informe publicado por su token; 404 si no existe o no esta publicado. */
    InspectionReport published(String publicToken);

    StoredPhoto publishedPhoto(String publicToken, long photoId);
}
