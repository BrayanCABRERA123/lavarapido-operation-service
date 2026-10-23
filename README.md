# operations-service

Operación del lavadero: operarios, disponibilidad y ausencias, asignación de reservas,
ejecución del servicio, calificaciones e informe de inspección del vehículo con fotos.
Es dueño del esquema `execution` (ADR-009). Puerto **3004**.

Stack: Spring Boot 4 · Java 21 · SQL Server (Liquibase) · RabbitMQ opcional.

## Cómo correrlo

Necesita la base compartida del repositorio `lavarapido-infra`, clonado al lado de este:

```bash
cd ../lavarapido-infra && docker compose up -d     # la primera vez: cp .env.example .env
cd ../lavarapido-operation-service
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
| GET | `/admin/assignments` | ADMIN | Asignaciones de reservas |
| GET | `/admin/assignments/{bookingId}/candidates` | ADMIN | Operarios libres para una reserva |
| PUT | `/admin/assignments/{bookingId}` | ADMIN | Asignar o reasignar a mano: `{operatorId, notes}` (notas para el operario, opcionales, hasta 300 caracteres; changelog 042) |
| GET · POST | `/admin/inspections/{bookingId}` | ADMIN | Ver o iniciar el informe de inspección |
| PATCH | `/admin/inspections/{bookingId}/phases/{phase}` | ADMIN | Abrir o cerrar una fase |
| POST · DELETE | `/admin/inspections/{bookingId}/findings[/{findingId}]` | ADMIN | Hallazgo con hasta 5 fotos (multipart, 2 MB cada una) |
| POST | `/admin/inspections/{bookingId}/publish`, `/share` | ADMIN | Publicar el informe y generar el enlace |
| GET | `/public/inspections/{token}[/photos/{photoId}]` | público | Informe publicado, sin sesión |
| GET | `/operator/services`, `/operator/ratings` | OPERATOR | Servicios asignados y calificaciones recibidas |
| POST | `/operator/services/{bookingId}/start`, `/finish` | OPERATOR | Iniciar y terminar el servicio |
| GET | `/operators/available` | token | Operarios libres en una franja (al reservar) |
| GET | `/operators/my-bookings` | CLIENT | Operario asignado a cada reserva del cliente |
| GET · POST | `/ratings/me`, `/ratings/{bookingId}` | CLIENT | Calificaciones propias y calificar un servicio |

Errores: `application/problem+json` con `code`.

## Reglas principales

- Al confirmarse una reserva se asigna sola al operario libre o al que eligió el cliente
  (RF-008, `AUTO_ASSIGN_ENABLED`); con `false` el administrador asigna a mano.
- Turnos, ausencias y fechas se leen en la zona del lavadero (`BOOKING_ZONE`, `America/Bogota`).
- Las reservas se leen y se mueven en booking-service con el token de quien llama; las tareas
  sin usuario (asignación automática) usan las rutas internas con `INTERNAL_API_KEY`.
- El informe de inspección publicado se abre con un token en el enlace, sin iniciar sesión.

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
