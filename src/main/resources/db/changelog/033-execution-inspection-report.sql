--liquibase formatted sql
-- Informe de inspeccion del vehiculo (RF-027). Hasta ahora vivia en el navegador del admin
-- (localStorage + IndexedDB) y el enlace publico solo abria en ese navegador. Ahora lo guarda
-- operations-service: el informe, sus 5 fases, los hallazgos y sus fotos.
-- booking_id no tiene FK: la reserva es de booking-service (ADR-004).

--changeset lavarapido:operations-033-inspection-report
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id WHERE s.name = 'execution' AND t.name = 'inspection_report'
CREATE TABLE [execution].inspection_report (
    inspection_report_id BIGINT        IDENTITY(1,1) NOT NULL,
    booking_id           BIGINT        NOT NULL,   -- booking.booking, sin FK
    booking_code         NVARCHAR(20)  NULL,
    vehicle              NVARCHAR(120) NULL,
    plate                NVARCHAR(15)  NULL,
    services             NVARCHAR(300) NULL,
    booking_date         DATE          NOT NULL,
    booking_time         TIME(0)       NOT NULL,
    duration_minutes     INT           NOT NULL,
    public_token         NVARCHAR(64)  NOT NULL,
    is_published         BIT           NOT NULL CONSTRAINT df_inspreport_published DEFAULT 0,
    published_at         DATETIME2(3)  NULL,
    created_at           DATETIME2(3)  NOT NULL CONSTRAINT df_inspreport_created DEFAULT SYSUTCDATETIME(),
    created_by           BIGINT        NULL,
    updated_at           DATETIME2(3)  NULL,
    updated_by           BIGINT        NULL,
    row_version          INT           NOT NULL CONSTRAINT df_inspreport_rv DEFAULT 1,
    CONSTRAINT pk_inspection_report PRIMARY KEY (inspection_report_id),
    CONSTRAINT uq_inspection_report_booking UNIQUE (booking_id),
    CONSTRAINT uq_inspection_report_token UNIQUE (public_token),
    CONSTRAINT ck_inspreport_published CHECK (
        (is_published = 0 AND published_at IS NULL) OR (is_published = 1 AND published_at IS NOT NULL))
);
--rollback DROP TABLE [execution].inspection_report;

--changeset lavarapido:operations-033-inspection-phase
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id WHERE s.name = 'execution' AND t.name = 'inspection_phase'
CREATE TABLE [execution].inspection_phase (
    inspection_phase_id  BIGINT       IDENTITY(1,1) NOT NULL,
    inspection_report_id BIGINT       NOT NULL,
    phase                NVARCHAR(20) NOT NULL,
    [status]             NVARCHAR(20) NOT NULL CONSTRAINT df_inspphase_status DEFAULT N'PENDING',
    started_at           DATETIME2(3) NULL,
    finished_at          DATETIME2(3) NULL,
    CONSTRAINT pk_inspection_phase PRIMARY KEY (inspection_phase_id),
    CONSTRAINT uq_inspection_phase UNIQUE (inspection_report_id, phase),
    CONSTRAINT fk_inspection_phase_report FOREIGN KEY (inspection_report_id) REFERENCES [execution].inspection_report(inspection_report_id),
    CONSTRAINT ck_inspphase_phase CHECK (phase IN (N'RECEPTION', N'EXTERIOR', N'INTERIOR', N'ENGINE', N'DELIVERY')),
    CONSTRAINT ck_inspphase_status CHECK ([status] IN (N'PENDING', N'IN_PROGRESS', N'DONE'))
);
--rollback DROP TABLE [execution].inspection_phase;

--changeset lavarapido:operations-033-inspection-finding
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id WHERE s.name = 'execution' AND t.name = 'inspection_finding'
CREATE TABLE [execution].inspection_finding (
    inspection_finding_id BIGINT        IDENTITY(1,1) NOT NULL,
    inspection_report_id  BIGINT        NOT NULL,
    phase                 NVARCHAR(20)  NOT NULL,
    area                  NVARCHAR(120) NOT NULL,
    severity              NVARCHAR(10)  NOT NULL,
    note                  NVARCHAR(500) NULL,
    created_at            DATETIME2(3)  NOT NULL CONSTRAINT df_inspfinding_created DEFAULT SYSUTCDATETIME(),
    created_by            BIGINT        NULL,
    deleted_at            DATETIME2(3)  NULL,
    deleted_by            BIGINT        NULL,
    CONSTRAINT pk_inspection_finding PRIMARY KEY (inspection_finding_id),
    CONSTRAINT fk_inspection_finding_report FOREIGN KEY (inspection_report_id) REFERENCES [execution].inspection_report(inspection_report_id),
    CONSTRAINT ck_inspfinding_phase CHECK (phase IN (N'RECEPTION', N'EXTERIOR', N'INTERIOR', N'ENGINE', N'DELIVERY')),
    CONSTRAINT ck_inspfinding_severity CHECK (severity IN (N'INFO', N'MINOR', N'MAJOR'))
);
CREATE NONCLUSTERED INDEX ix_inspection_finding_report ON [execution].inspection_finding (inspection_report_id);
--rollback DROP TABLE [execution].inspection_finding;

--changeset lavarapido:operations-033-inspection-photo
--comment: Las fotos llegan comprimidas desde la pantalla (lado mayor 1280 px); el servicio acepta hasta 2 MB
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id WHERE s.name = 'execution' AND t.name = 'inspection_photo'
CREATE TABLE [execution].inspection_photo (
    inspection_photo_id   BIGINT         IDENTITY(1,1) NOT NULL,
    inspection_finding_id BIGINT         NOT NULL,
    content_type          NVARCHAR(30)   NOT NULL,
    content               VARBINARY(MAX) NOT NULL,
    size_bytes            INT            NOT NULL,
    created_at            DATETIME2(3)   NOT NULL CONSTRAINT df_inspphoto_created DEFAULT SYSUTCDATETIME(),
    created_by            BIGINT         NULL,
    CONSTRAINT pk_inspection_photo PRIMARY KEY (inspection_photo_id),
    CONSTRAINT fk_inspection_photo_finding FOREIGN KEY (inspection_finding_id) REFERENCES [execution].inspection_finding(inspection_finding_id),
    CONSTRAINT ck_inspphoto_type CHECK (content_type IN (N'image/jpeg', N'image/png')),
    CONSTRAINT ck_inspphoto_size CHECK (size_bytes > 0 AND size_bytes <= 2097152)
);
CREATE NONCLUSTERED INDEX ix_inspection_photo_finding ON [execution].inspection_photo (inspection_finding_id);
--rollback DROP TABLE [execution].inspection_photo;

--changeset lavarapido:operations-033-tr-touch-inspection-report splitStatements:false
--comment: Trigger que pisa updated_at y row_version. El codigo jamas escribe esas dos columnas
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys.triggers WHERE name = 'tr_touch_inspection_report'
CREATE TRIGGER [execution].tr_touch_inspection_report
ON [execution].[inspection_report]
AFTER UPDATE
AS
BEGIN
    SET NOCOUNT ON;
    IF TRIGGER_NESTLEVEL(@@PROCID) > 1 RETURN;

    UPDATE x
       SET updated_at  = SYSUTCDATETIME(),
           row_version = x.row_version + 1
      FROM [execution].[inspection_report] x
      JOIN inserted i ON i.inspection_report_id = x.inspection_report_id;
END
--rollback DROP TRIGGER [execution].tr_touch_inspection_report;
