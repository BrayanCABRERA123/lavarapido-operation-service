--liquibase formatted sql
-- Estados de la ejecución de cada línea de la reserva, con códigos en inglés como los de booking
-- (ADR-010). Ids fijos: el código de la aplicación los usa por código, no por número.

--changeset lavarapido:operations-105-seed-execution-status
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM [execution].execution_status
SET IDENTITY_INSERT [execution].execution_status ON;
INSERT INTO [execution].execution_status (execution_status_id, code, name, is_final, display_order) VALUES
    (1, N'PENDING',     N'Pendiente',   0, 1),
    (2, N'IN_PROGRESS', N'En proceso',  0, 2),
    (3, N'PAUSED',      N'Pausada',     0, 3),
    (4, N'COMPLETED',   N'Finalizada',  1, 4),
    (5, N'WITH_ISSUE',  N'Con novedad', 1, 5);
SET IDENTITY_INSERT [execution].execution_status OFF;
