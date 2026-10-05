--liquibase formatted sql
-- Las 5 tablas del operations-service (esquema execution). Salen del DDL general del proyecto
-- (lavarapido-6-services-sqlserver.sql y 06-data/models.md, seccion 06). Los triggers de
-- auditoria (tr_touch_*) y el de ausencias solapadas van aqui con sus tablas.

--changeset lavarapido:operations-007-schema-execution
--comment: Esquema execution (operarios, disponibilidad, ausencias y ejecucion), es de este servicio
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys.schemas WHERE name = 'execution'
CREATE SCHEMA [execution];

--changeset lavarapido:operations-007-execution-status
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id WHERE s.name = 'execution' AND t.name = 'execution_status'
CREATE TABLE [execution].execution_status (
    execution_status_id SMALLINT     IDENTITY(1,1) NOT NULL,
    code          NVARCHAR(30) NOT NULL,
    name          NVARCHAR(60) NOT NULL,
    is_final      BIT          NOT NULL CONSTRAINT df_estatus_final DEFAULT 0,
    display_order SMALLINT     NOT NULL CONSTRAINT df_estatus_order DEFAULT 0,
    is_active     BIT          NOT NULL CONSTRAINT df_estatus_active DEFAULT 1,
    created_at    DATETIME2(3) NOT NULL CONSTRAINT df_estatus_created DEFAULT SYSUTCDATETIME(),
    created_by    BIGINT       NULL,
    updated_at    DATETIME2(3) NULL,
    updated_by    BIGINT       NULL,
    deleted_at    DATETIME2(3) NULL,
    deleted_by    BIGINT       NULL,
    row_version   INT          NOT NULL CONSTRAINT df_estatus_rv DEFAULT 1,
    CONSTRAINT pk_execution_status PRIMARY KEY (execution_status_id),
    CONSTRAINT uq_execution_status_code UNIQUE (code)
);

--changeset lavarapido:operations-007-operator
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id WHERE s.name = 'execution' AND t.name = 'operator'
CREATE TABLE [execution].operator (
    operator_id INT          IDENTITY(1,1) NOT NULL,
    user_id     BIGINT       NOT NULL,   -- [security].app_user (sin FK: otro servicio)
    hired_on    DATE         NOT NULL,
    is_active   BIT          NOT NULL CONSTRAINT df_operator_active DEFAULT 1,
    created_at  DATETIME2(3) NOT NULL CONSTRAINT df_operator_created DEFAULT SYSUTCDATETIME(),
    created_by  BIGINT       NULL,
    updated_at  DATETIME2(3) NULL,
    updated_by  BIGINT       NULL,
    deleted_at  DATETIME2(3) NULL,
    deleted_by  BIGINT       NULL,
    row_version INT          NOT NULL CONSTRAINT df_operator_rv DEFAULT 1,
    CONSTRAINT pk_operator PRIMARY KEY (operator_id),
    CONSTRAINT uq_operator_user UNIQUE (user_id)
);

--changeset lavarapido:operations-007-operator-availability
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id WHERE s.name = 'execution' AND t.name = 'operator_availability'
CREATE TABLE [execution].operator_availability (
    operator_availability_id INT          IDENTITY(1,1) NOT NULL,
    operator_id INT          NOT NULL,
    day_of_week SMALLINT     NOT NULL,
    starts_at   TIME(0)      NOT NULL,
    ends_at     TIME(0)      NOT NULL,
    is_active   BIT          NOT NULL CONSTRAINT df_oavail_active DEFAULT 1,
    created_at  DATETIME2(3) NOT NULL CONSTRAINT df_oavail_created DEFAULT SYSUTCDATETIME(),
    created_by  BIGINT       NULL,
    updated_at  DATETIME2(3) NULL,
    updated_by  BIGINT       NULL,
    deleted_at  DATETIME2(3) NULL,
    deleted_by  BIGINT       NULL,
    row_version INT          NOT NULL CONSTRAINT df_oavail_rv DEFAULT 1,
    CONSTRAINT pk_operator_availability PRIMARY KEY (operator_availability_id),
    CONSTRAINT uq_operator_availability UNIQUE (operator_id, day_of_week),
    CONSTRAINT fk_operator_availability_operator FOREIGN KEY (operator_id) REFERENCES [execution].operator(operator_id),
    CONSTRAINT ck_oavail_day CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_oavail_range CHECK (ends_at > starts_at)
);

--changeset lavarapido:operations-007-operator-absence
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id WHERE s.name = 'execution' AND t.name = 'operator_absence'
CREATE TABLE [execution].operator_absence (
    operator_absence_id INT           IDENTITY(1,1) NOT NULL,
    operator_id INT           NOT NULL,
    starts_at   DATETIME2(3)  NOT NULL,
    ends_at     DATETIME2(3)  NOT NULL,
    reason      NVARCHAR(120) NOT NULL,
    created_at  DATETIME2(3)  NOT NULL CONSTRAINT df_oabs_created DEFAULT SYSUTCDATETIME(),
    created_by  BIGINT        NULL,
    updated_at  DATETIME2(3)  NULL,
    updated_by  BIGINT        NULL,
    deleted_at  DATETIME2(3)  NULL,
    deleted_by  BIGINT        NULL,
    row_version INT           NOT NULL CONSTRAINT df_oabs_rv DEFAULT 1,
    CONSTRAINT pk_operator_absence PRIMARY KEY (operator_absence_id),
    CONSTRAINT fk_operator_absence_operator FOREIGN KEY (operator_id) REFERENCES [execution].operator(operator_id),
    CONSTRAINT ck_oabs_range CHECK (ends_at > starts_at)
);
CREATE NONCLUSTERED INDEX ix_operator_absence_operator ON [execution].operator_absence (operator_id, starts_at);

--changeset lavarapido:operations-007-service-execution
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id WHERE s.name = 'execution' AND t.name = 'service_execution'
CREATE TABLE [execution].service_execution (
    service_execution_id BIGINT        IDENTITY(1,1) NOT NULL,
    booking_service_id   BIGINT        NOT NULL,   -- [booking].booking_service (sin FK: otro servicio)
    operator_id          INT           NOT NULL,
    execution_status_id  SMALLINT      NOT NULL,
    started_at           DATETIME2(3)  NULL,
    finished_at          DATETIME2(3)  NULL,
    quality_rating       SMALLINT      NULL,
    quality_comment      NVARCHAR(500) NULL,
    rated_at             DATETIME2(3)  NULL,
    is_comment_visible   BIT           NOT NULL CONSTRAINT df_sexec_visible DEFAULT 1,
    created_at           DATETIME2(3)  NOT NULL CONSTRAINT df_sexec_created DEFAULT SYSUTCDATETIME(),
    created_by           BIGINT        NULL,
    updated_at           DATETIME2(3)  NULL,
    updated_by           BIGINT        NULL,
    deleted_at           DATETIME2(3)  NULL,
    deleted_by           BIGINT        NULL,
    row_version          INT           NOT NULL CONSTRAINT df_sexec_rv DEFAULT 1,
    CONSTRAINT pk_service_execution PRIMARY KEY (service_execution_id),
    CONSTRAINT uq_service_execution_booking_service UNIQUE (booking_service_id),
    CONSTRAINT fk_service_execution_operator FOREIGN KEY (operator_id) REFERENCES [execution].operator(operator_id),
    CONSTRAINT fk_service_execution_status FOREIGN KEY (execution_status_id) REFERENCES [execution].execution_status(execution_status_id),
    CONSTRAINT ck_sexec_rating CHECK (quality_rating IS NULL OR quality_rating BETWEEN 1 AND 5),
    CONSTRAINT ck_sexec_range CHECK (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at),
    CONSTRAINT ck_sexec_comment CHECK (quality_comment IS NULL OR quality_rating IS NOT NULL),
    CONSTRAINT ck_sexec_rated CHECK (
        (quality_rating IS NULL AND rated_at IS NULL) OR
        (quality_rating IS NOT NULL AND rated_at IS NOT NULL)
    )
);
CREATE NONCLUSTERED INDEX ix_service_execution_operator ON [execution].service_execution (operator_id, started_at);

--changeset lavarapido:operations-007-tr-operator-absence-overlap splitStatements:false
--comment: Dos ausencias del mismo operario no se pueden traslapar (models.md, operator_absence)
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys.triggers WHERE name = 'tr_operator_absence_overlap'
CREATE TRIGGER [execution].tr_operator_absence_overlap
ON [execution].[operator_absence]
AFTER INSERT, UPDATE
AS
BEGIN
    SET NOCOUNT ON;
    IF EXISTS (
        SELECT 1
          FROM inserted i
          JOIN [execution].[operator_absence] a
            ON a.operator_id = i.operator_id
           AND a.operator_absence_id <> i.operator_absence_id
           AND a.deleted_at IS NULL
           AND i.deleted_at IS NULL
           AND a.starts_at < i.ends_at
           AND i.starts_at < a.ends_at
    )
    BEGIN
        THROW 50002, 'The operator already has an absence in that period.', 1;
    END
END

--changeset lavarapido:operations-007-tr-touch-operator splitStatements:false
--comment: Trigger que pisa updated_at y row_version. El codigo jamas escribe esas dos columnas
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys.triggers WHERE name = 'tr_touch_operator'
CREATE TRIGGER [execution].tr_touch_operator
ON [execution].[operator]
AFTER UPDATE
AS
BEGIN
    SET NOCOUNT ON;
    IF TRIGGER_NESTLEVEL(@@PROCID) > 1 RETURN;

    UPDATE x
       SET updated_at  = SYSUTCDATETIME(),
           row_version = x.row_version + 1
      FROM [execution].[operator] x
      JOIN inserted i ON i.operator_id = x.operator_id;
END

--changeset lavarapido:operations-007-tr-touch-operator-availability splitStatements:false
--comment: Trigger que pisa updated_at y row_version. El codigo jamas escribe esas dos columnas
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys.triggers WHERE name = 'tr_touch_operator_availability'
CREATE TRIGGER [execution].tr_touch_operator_availability
ON [execution].[operator_availability]
AFTER UPDATE
AS
BEGIN
    SET NOCOUNT ON;
    IF TRIGGER_NESTLEVEL(@@PROCID) > 1 RETURN;

    UPDATE x
       SET updated_at  = SYSUTCDATETIME(),
           row_version = x.row_version + 1
      FROM [execution].[operator_availability] x
      JOIN inserted i ON i.operator_availability_id = x.operator_availability_id;
END

--changeset lavarapido:operations-007-tr-touch-operator-absence splitStatements:false
--comment: Trigger que pisa updated_at y row_version. El codigo jamas escribe esas dos columnas
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys.triggers WHERE name = 'tr_touch_operator_absence'
CREATE TRIGGER [execution].tr_touch_operator_absence
ON [execution].[operator_absence]
AFTER UPDATE
AS
BEGIN
    SET NOCOUNT ON;
    IF TRIGGER_NESTLEVEL(@@PROCID) > 1 RETURN;

    UPDATE x
       SET updated_at  = SYSUTCDATETIME(),
           row_version = x.row_version + 1
      FROM [execution].[operator_absence] x
      JOIN inserted i ON i.operator_absence_id = x.operator_absence_id;
END

--changeset lavarapido:operations-007-tr-touch-service-execution splitStatements:false
--comment: Trigger que pisa updated_at y row_version. El codigo jamas escribe esas dos columnas
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys.triggers WHERE name = 'tr_touch_service_execution'
CREATE TRIGGER [execution].tr_touch_service_execution
ON [execution].[service_execution]
AFTER UPDATE
AS
BEGIN
    SET NOCOUNT ON;
    IF TRIGGER_NESTLEVEL(@@PROCID) > 1 RETURN;

    UPDATE x
       SET updated_at  = SYSUTCDATETIME(),
           row_version = x.row_version + 1
      FROM [execution].[service_execution] x
      JOIN inserted i ON i.service_execution_id = x.service_execution_id;
END
