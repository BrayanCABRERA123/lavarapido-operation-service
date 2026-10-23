--liquibase formatted sql
-- B-01: indicaciones del administrador para el operario al asignar una reserva (opcional).

--changeset lavarapido:execution-042-assignment-notes
--comment: Notas de la asignación para el operario
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys.columns WHERE object_id = OBJECT_ID('execution.service_execution') AND name = 'assignment_notes'
ALTER TABLE [execution].service_execution ADD assignment_notes NVARCHAR(300) NULL;
--rollback ALTER TABLE [execution].service_execution DROP COLUMN assignment_notes;
