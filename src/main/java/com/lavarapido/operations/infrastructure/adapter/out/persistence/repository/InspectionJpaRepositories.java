package com.lavarapido.operations.infrastructure.adapter.out.persistence.repository;

import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.InspectionFindingJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.InspectionPhaseJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.InspectionPhotoJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.InspectionReportJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Repositorios Spring Data del informe de inspeccion (anidados: ver JpaConfig). */
public final class InspectionJpaRepositories {

    private InspectionJpaRepositories() {
    }

    public interface InspectionReportJpaRepository extends JpaRepository<InspectionReportJpaEntity, Long> {

        Optional<InspectionReportJpaEntity> findByBookingId(long bookingId);

        Optional<InspectionReportJpaEntity> findByPublicToken(String publicToken);

        /** Cualquier cambio del agregado toca la fila del informe: el trigger actualiza updated_at. */
        @Modifying(flushAutomatically = true, clearAutomatically = true)
        @Query("update InspectionReportJpaEntity r set r.updatedBy = :actor where r.id = :id")
        void touch(@Param("id") long id, @Param("actor") long actor);
    }

    public interface InspectionPhaseJpaRepository extends JpaRepository<InspectionPhaseJpaEntity, Long> {

        List<InspectionPhaseJpaEntity> findByReportId(long reportId);
    }

    public interface InspectionFindingJpaRepository extends JpaRepository<InspectionFindingJpaEntity, Long> {

        List<InspectionFindingJpaEntity> findByReportIdAndDeletedAtIsNullOrderByIdAsc(long reportId);
    }

    /** Ids de las fotos sin traer el binario. */
    public interface PhotoRef {
        Long getId();

        Long getFindingId();
    }

    /** Foto con el informe al que pertenece (para no servir fotos de otro informe). */
    public interface PhotoWithReport {
        Long getId();

        Long getReportId();

        String getContentType();

        byte[] getContent();
    }

    public interface InspectionPhotoJpaRepository extends JpaRepository<InspectionPhotoJpaEntity, Long> {

        @Query("select p.id as id, p.findingId as findingId from InspectionPhotoJpaEntity p "
                + "where p.findingId in :findingIds order by p.id")
        List<PhotoRef> refsOf(@Param("findingIds") Collection<Long> findingIds);

        @Query("select p.id as id, f.reportId as reportId, p.contentType as contentType, p.content as content "
                + "from InspectionPhotoJpaEntity p, InspectionFindingJpaEntity f "
                + "where p.id = :id and f.id = p.findingId and f.deletedAt is null")
        Optional<PhotoWithReport> withReport(@Param("id") long id);
    }
}
