--liquibase formatted sql
-- Una reserva con operario que terminó sin registrarse (no se inició, o se inició y no se finalizó)
-- ya no se cierra sola como "no asistió": la resuelve el administrador. Esta columna guarda cuándo se
-- avisó al operario y a los administradores, para avisar una sola vez.

--changeset lavarapido:execution-040-follow-up-notice
--comment: Cuándo se avisó que el servicio terminó sin registrarse
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys.columns WHERE object_id = OBJECT_ID('execution.service_execution') AND name = 'follow_up_notice_at'
ALTER TABLE [execution].service_execution ADD follow_up_notice_at DATETIME2(3) NULL;
--rollback ALTER TABLE [execution].service_execution DROP COLUMN follow_up_notice_at;
