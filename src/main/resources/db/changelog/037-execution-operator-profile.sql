--liquibase formatted sql
-- Especialidad y habilidades del operario (ficha del operario en web y móvil). Hasta ahora la
-- pantalla las mostraba vacías porque no se guardaban. Van aparte de execution.operator: una fila
-- de perfil por operario y una por habilidad (nombre + nivel). Guardar reemplaza la lista completa.

--changeset lavarapido:operations-037-operator-profile
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id WHERE s.name = 'execution' AND t.name = 'operator_profile'
CREATE TABLE [execution].operator_profile (
    operator_id INT          NOT NULL,
    specialty   NVARCHAR(80) NULL,
    updated_at  DATETIME2(3) NOT NULL CONSTRAINT df_operator_profile_updated DEFAULT SYSUTCDATETIME(),
    updated_by  BIGINT       NULL,
    CONSTRAINT pk_operator_profile PRIMARY KEY (operator_id),
    CONSTRAINT fk_operator_profile_operator FOREIGN KEY (operator_id) REFERENCES [execution].operator(operator_id)
);
--rollback DROP TABLE [execution].operator_profile;

--changeset lavarapido:operations-037-operator-skill
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id WHERE s.name = 'execution' AND t.name = 'operator_skill'
CREATE TABLE [execution].operator_skill (
    operator_skill_id INT          IDENTITY(1,1) NOT NULL,
    operator_id       INT          NOT NULL,
    name              NVARCHAR(60) NOT NULL,
    skill_level       NVARCHAR(12) NOT NULL,
    display_order     SMALLINT     NOT NULL,
    CONSTRAINT pk_operator_skill PRIMARY KEY (operator_skill_id),
    CONSTRAINT fk_operator_skill_operator FOREIGN KEY (operator_id) REFERENCES [execution].operator(operator_id),
    CONSTRAINT uq_operator_skill_name UNIQUE (operator_id, name),
    CONSTRAINT ck_operator_skill_level CHECK (skill_level IN ('CERTIFIED', 'EXPERT', 'ADVANCED'))
);
--rollback DROP TABLE [execution].operator_skill;
