# operations-service

Operación del lavadero: operarios, disponibilidad y ausencias, asignación de reservas,
ejecución del servicio, calificaciones e informe de inspección del vehículo con fotos.
Es dueño del esquema `execution` (ADR-009). Puerto **3004**. Pruebas: `./mvnw test`, 110 al 24-10-2026 (con las ramas de la revisión del 23-10 fusionadas), sin base de datos.

Stack: Spring Boot 4 · Java 21 · SQL Server (Liquibase) · RabbitMQ opcional.

## Cómo correrlo

Necesita la base compartida del repositorio `lavarapido-infra`, clonado al lado de este:

```bash
cd ../lavarapido-infra && docker compose up -d     # la primera vez: cp .env.example .env
cd ../lavarapido-operations-service
./mvnw spring-boot:run                              # Windows: .\mvnw.cmd spring-boot:run
curl http://localhost:3004/actuator/health
```

Los secretos se leen de `../lavarapido-infra/.env`; un `.env` en esta carpeta lo reemplaza.
Con todo el sistema: `docker compose --profile app up -d` desde `lavarapido-infra`.

**Swagger UI:** http://localhost:3004/swagger-ui/index.html (solo con el perfil `dev`).

## API (`/api/v1`)

Desde afuera se llega por el API Gateway (puerto 8080).

| Método | Ruta | Acceso | Para qué |
|---|---|---|---|
| GET | `/admin/operators`, `/admin/operators/summary` | ADMIN | Lista de operarios y resumen |
| GET | `/admin/operators/{id}/metrics` | ADMIN | Servicios y calificación de un operario |
| PATCH | `/admin/operators/{id}/status` | ADMIN | Activar o desactivar un operario |
| PUT | `/admin/operators/{id}/availability` | ADMIN | Turno semanal |
| GET · POST · DELETE | `/admin/operators/{id}/absences[/{absenceId}]` | ADMIN | Ausencias (vacaciones, incapacidades) |
| GET · PUT | `/admin/operators/profiles`, `/admin/operators/{id}/profile` | ADMIN | Perfil del operario (especialidad y habilidades por nivel) |
| GET | `/admin/assignments` | ADMIN | Asignaciones de reservas (con las `notes` de cada una) |
| GET | `/admin/assignments/unassigned` | ADMIN | Reservas programadas o confirmadas, sin terminar y sin operario |
| GET | `/admin/assignments/to-close` | ADMIN | "Por cerrar": terminaron con operario y nadie las registró (`NOT_STARTED` o `NOT_FINISHED`) |
| POST | `/admin/assignments/{bookingId}/attended` | ADMIN | Cerrar como atendida una reserva por cerrar (409 `BOOKING_NOT_TO_CLOSE` si no lo está) |
| GET | `/admin/assignments/{bookingId}/candidates` | ADMIN | Operarios para una reserva y por qué no se pueden asignar (`OPERATOR_ACCOUNT_DISABLED`, `OPERATOR_BUSY`...) |
| PUT | `/admin/assignments/{bookingId}` | ADMIN | Asignar o reasignar a mano: `{operatorId, notes}` (notas para el operario, opcionales, hasta 300 caracteres; changelog 042). Reasignar sin notas conserva las anteriores |
| GET · POST | `/admin/inspections/{bookingId}` | ADMIN | Ver o iniciar el informe de inspección |
| PATCH | `/admin/inspections/{bookingId}/phases/{phase}` | ADMIN | Abrir o cerrar una fase |
| POST · DELETE | `/admin/inspections/{bookingId}/findings[/{findingId}]` | ADMIN | Hallazgo con hasta 5 fotos (multipart, 2 MB cada una) |
| POST | `/admin/inspections/{bookingId}/publish` | ADMIN | Publicar el informe y generar el enlace |
| POST | `/admin/inspections/{bookingId}/share` | ADMIN | Avisar al cliente (204). 503 `NOTIFICATION_NOT_SENT` si el aviso no se pudo publicar en RabbitMQ o la mensajería está apagada |
| GET | `/admin/inspections/{bookingId}/photos/{photoId}` | ADMIN | Foto de un hallazgo |
| GET | `/public/inspections/{token}[/photos/{photoId}]` | público | Informe publicado, sin sesión |
| GET | `/operator/services`, `/operator/ratings` | OPERATOR | Servicios asignados (con `notes`, `startableAt` y `startableUntil`) y calificaciones recibidas |
| POST | `/operator/services/{bookingId}/start`, `/finish` | OPERATOR | Iniciar y terminar el servicio |
| GET | `/operators/available` | token | Operarios libres en una franja (al reservar) |
| GET | `/operators/my-bookings` | CLIENT | Operario asignado a cada reserva del cliente |
| GET · POST | `/ratings/me`, `/ratings/{bookingId}` | CLIENT | Calificaciones propias y calificar un servicio |

Errores: `application/problem+json` con `code`. Además de los de negocio: 409 `CONFLICT` (índice
único, trigger o bloqueo de otra transacción), 503 `DATABASE_UNAVAILABLE` (la base no responde),
503 `BOOKING_SERVICE_UNAVAILABLE` y 503 `SECURITY_SERVICE_UNAVAILABLE` (el vecino no responde).

## Reglas principales

- Al confirmarse una reserva se asigna sola al operario libre o al que eligió el cliente
  (RF-008, `AUTO_ASSIGN_ENABLED`); con `false` el administrador asigna a mano.
- Una cuenta desactivada en security no recibe servicios. Si security no responde, la asignación
  falla con 503 `SECURITY_SERVICE_UNAVAILABLE` en vez de asignar a ciegas (A-13).
- Al reprogramar, el operario se conserva solo si todavía puede a la hora nueva; si no, pasa a otro
  libre y, si no hay nadie, la reserva queda sin operario y se publica `execution.operator_unassigned`
  para avisar al operario y a los administradores (E-12).
- Se inicia desde 15 minutos antes de la hora de la reserva y hasta `START_LATE_LIMIT` (4 h) después
  de la hora de fin; después responde 409 `SERVICE_TOO_LATE` y la reserva se cierra desde "Por cerrar" (A-12).
- Iniciar, terminar y cerrar guardan la ejecución antes de mover la reserva en booking; si booking
  responde 409 porque ya está en ese estado, el paso se da por hecho. Una reserva COMPLETED con líneas
  sin terminar sale en "Por cerrar" (OPS-01).
- Las ejecuciones de una reserva se leen con bloqueo pesimista (UPDLOCK) al iniciar, terminar, cerrar,
  calificar y asignar, y el inicio bloquea además la fila del operario; la asignación usa también
  `sp_getapplock` y no llama a booking ni a security mientras lo tiene (OPS-03, OPS-11). No se usa
  `@Version`: el trigger `tr_touch_service_execution` ya incrementa `row_version`.
- Turnos, ausencias y fechas se leen en la zona del lavadero (`BOOKING_ZONE`, `America/Bogota`).
- Las reservas se leen y se mueven en booking-service con el token de quien llama; las tareas
  sin usuario (asignación automática) usan las rutas internas con `INTERNAL_API_KEY`.
- Iniciar y terminar un servicio (`/api/v1/operator/services/**`) es el único camino del operario:
  aquí se valida que la reserva esté asignada a él y la regla de los 15 minutos, y booking solo
  acepta sus rutas `/api/v1/operator/bookings/**` con el token **y** `INTERNAL_API_KEY` (E-01).
  Sin la llave, el operario no puede iniciar ni terminar servicios.
- El informe de inspección publicado se abre con un token en el enlace, sin iniciar sesión.

## Eventos (RabbitMQ, con `MESSAGING_ENABLED=true`)

Publica en `carwash.events`, con publisher confirms y `mandatory` (lo que el broker rechaza o no puede
enrutar queda en el log con su `eventId`): `execution.operator_assigned`, `execution.operator_unassigned`,
`execution.service_started`, `execution.service_completed`, `execution.service_not_started`,
`execution.service_not_finished` y `execution.inspection_shared`.

Escucha `booking.confirmed` en la cola `operations-service.booking-confirmed` para la asignación
automática. Si booking, security o la base no responden, se reintenta 3 veces y luego el mensaje va a
`operations-service.booking-confirmed.dlq` (por `carwash.events.dlx`); un mensaje que no es JSON va
directo a la DLQ. La cola de antes, `operations-service.events`, no tenía DLX: al arrancar se desenlaza,
sus mensajes pasan a la nueva y se borra (OPS-04).

## Variables

| Variable | Por defecto |
|---|---|
| `OPERATIONS_PORT` | `3004` |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | SQL Server local, `sa`, sin valor |
| `JWT_SECRET`, `JWT_ISSUER`, `JWT_AUDIENCE` | los mismos de security-service |
| `BOOKING_SERVICE_URL`, `BOOKING_INTERNAL_URL` | `http://localhost:3003/api/v1`, `http://localhost:3003` |
| `SECURITY_SERVICE_URL`, `SECURITY_INTERNAL_URL` | `http://localhost:3001/api/v1`, `http://localhost:3001` |
| `INTERNAL_API_KEY` | sin valor (llave compartida entre servicios) |
| `AUTO_ASSIGN_ENABLED` | `true` |
| `START_LATE_LIMIT` | `PT4H` (hasta cuánto después de la hora de fin se puede iniciar) |
| `FOLLOW_UP_ENABLED`, `FOLLOW_UP_INTERVAL` | `true`, `PT5M` (tarea de "Por cerrar") |
| `BOOKING_EXPIRY_GRACE_MINUTES`, `FOLLOW_UP_LOOK_BACK_DAYS` | `60`, `28` |
| `MESSAGING_ENABLED` | `false` (los eventos solo van al log) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200,http://localhost:8081` |

## Estructura

```
domain/          modelo, reglas, puertos de entrada y salida
application/     casos de uso
infrastructure/  adapter/in/web (controladores), adapter/out (persistencia, clientes REST), config
```

Migraciones en `src/main/resources/db/changelog` (tabla `DATABASECHANGELOG_OPERATIONS`).

## Pruebas

```bash
./mvnw test
```

Corren sin base de datos ni RabbitMQ (Mockito, MockMvc y servidores HTTP falsos para booking y
security). Cubren, entre otras: la asignación manual y automática (candado, cuenta desactivada,
reprogramación, llamadas fuera del candado), iniciar y terminar (también con booking caído o con
409), "Por cerrar", el límite para iniciar, el informe de inspección y su aviso, el listener de
`booking.confirmed` con booking caído, la DLQ, las confirmaciones del broker, los errores de base de
datos y la lista de operarios con más de 100 cuentas. No hay pruebas contra SQL Server (F-03): el
`UPDLOCK`, `sp_getapplock` y las migraciones se prueban en vivo.

## Proyecto LavaRápido

Este repositorio es parte de LavaRápido (Vehicle Washing), proyecto formativo del Tecnólogo en
Análisis y Desarrollo de Software (ADSO), ficha 3145556, SENA Regional Huila.

| Recurso | Enlace |
|---|---|
| Documentación y manuales (ES/EN, MD · PDF · Word) | [lavarapido-docs](https://github.com/BrayanCABRERA123/lavarapido-docs#readme) |
| Manual técnico | [09_MANUALES/ES/MD/manual-tecnico.md](https://github.com/BrayanCABRERA123/lavarapido-docs/blob/main/09_MANUALES/ES/MD/manual-tecnico.md) |
| Manual de instalación | [09_MANUALES/ES/MD/manual-de-instalacion.md](https://github.com/BrayanCABRERA123/lavarapido-docs/blob/main/09_MANUALES/ES/MD/manual-de-instalacion.md) |
| Levantar todo el sistema | [lavarapido-infra](https://github.com/BrayanCABRERA123/lavarapido-infra) (`docker compose --profile app up -d`) |
| Aplicación desplegada | https://lavarapidovehicular.northcentralus.cloudapp.azure.com |

**Ramas y commits:** se trabaja en `develop` con ramas `feature/`, `fix/` y `docs/` que se
fusionan con `git merge --no-ff`; los commits siguen Conventional Commits
(`feat(scope): ...`, `fix(scope): ...`). Detalle en la
[guía de git del equipo](https://github.com/BrayanCABRERA123/lavarapido-docs#guía-de-git-del-equipo).

**Equipo:** Brayan Estiven Patiño Cabrera, Juan Camilo Guilombo Osorio y Merari Urbano Quintero.
