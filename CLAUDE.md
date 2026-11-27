# PayAF: Smart Payment Router (arquitectura hexagonal)

Enrutador de pagos multi-PSP con failover. Es un proyecto para aprender arquitectura hexagonal y se publica en `pedrogomez.dev/proyecto/PayAF`.
El plan completo está en `~/.claude/plans/quiero-hacer-una-app-vivid-kay-*.md`.

## Modelo de 3 agentes
- **Orquestador**: la sesión principal (Opus 5.5, `/effort xhigh`). Parte el trabajo en tareas pequeñas con criterios de aceptación, delega y revisa. No escribe código de producción salvo arreglos triviales.
- **developer** (`.claude/agents/developer.md`, Opus, esfuerzo medio): implementa.
- **tester** (`.claude/agents/tester.md`, Opus, esfuerzo alto): verifica y da APROBADO o RECHAZADO. No arregla.
- Bucle: tarea → developer → tester → si sale RECHAZADO, vuelve al developer con el informe → el orquestador revisa el diff y acepta.

## Stack
Java 21, Spring Boot 4.2 (JUnit 6), Maven multimódulo (`mvn.cmd` en PowerShell). PostgreSQL (Docker en local, Neon en producción) y un adaptador en memoria.
El panel es HTML/CSS/JS sin framework, servido desde `payaf-bootstrap/src/main/resources/static`, con SSE. Despliegue en Cloudflare Containers detrás de un Worker en la ruta de la zona.

## Reglas de dependencia
domain (Java puro) ← application (puertos + casos de uso) ← adapters. Solo bootstrap usa Spring para cablear.
Ningún adaptador importa otro. ArchUnit lo hace cumplir.

Textos de la UI y comentarios en español de España. Los identificadores, en inglés.
