# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Stack

HTML, CSS y JS sin framework, servidos por Spring Boot desde `payaf-bootstrap/src/main/resources/static`. Los datos llegan de la API REST propia (`/api/...`) y los eventos en vivo por SSE (`/api/events/stream`). Se publica en `pedrogomez.dev/proyecto/PayAF`, detrás de un Worker de Cloudflare con Containers, así que todas las rutas tienen que ser relativas: la app vive bajo ese prefijo.

## Users

Recruiters y entrevistadores técnicos que llegan desde el portfolio de Pedro Gómez (pedrogomez.dev). Pasan entre uno y tres minutos. Quieren comprobar que el autor sabe diseñar backend serio: arquitectura hexagonal, resiliencia y pagos. No van a leer documentación antes de tocar.

## Product Purpose

PayAF es un enrutador de pagos multi-PSP con failover, hecho para aprender y para demostrar la arquitectura hexagonal. El panel es la demo en vivo. El visitante tumba una pasarela (503), le mete latencia o le sube la tasa de error, lanza un pago y ve:
- por qué pasarela sale el pago y por qué se descartan las demás;
- el failover cuando una falla;
- el circuit breaker abriéndose y recuperándose;
- qué puertos y adaptadores ha tocado cada operación, sin que el dominio cambie.

El panel ha cumplido si el visitante, sin ayuda, provoca un failover y entiende qué adaptador intervino.

## Positioning

El dominio no sabe qué pasarela cobra. Decide por reglas de negocio (divisa, país, comisión, estado del circuito) y las pasarelas son adaptadores intercambiables. La demo hace visible lo que en otros proyectos solo se afirma: se cambia el comportamiento de un adaptador en caliente y el núcleo sigue igual.

## Operating Context

- **Pasarelas simuladas**, cada una con su formato nativo (anti-corruption layer):
  - Stripe: `pi_…`, `card_declined`.
  - Adyen: `pspReference`, `Refused`.
  - Redsys: `Ds_Response 0190`.
  - Mock: el último recurso.
- **Perfiles de comisión:**

  | Pasarela | Comisión | Divisas y países |
  |---|---|---|
  | Stripe | 290 pb + 0,25 | EUR, USD, GBP |
  | Adyen | 250 pb + 0,30 | EUR, USD, GBP, JPY |
  | Redsys | 120 pb | Solo EUR y España |
  | Mock | 900 pb | Todas |

- **Rechazo determinista:** un importe acabado en ,51 se rechaza (DECLINED) y no hay failover.
- **Circuit breaker:** se abre tras 3 fallos técnicos seguidos y pasa a HALF_OPEN a los 15 s.
- **Ciclo de vida del pago:** autorizar, capturar, cancelar y reembolsar. Hay idempotencia por clave y webhooks firmados que llegan después de capturar o reembolsar.
- **Perfiles de persistencia:** `memory` y `postgres`. El panel tiene que funcionar igual en los dos.

## Capabilities and Constraints

- **API disponible:**
  - `POST/GET /api/payments`
  - `/api/payments/{id}/capture|cancel|refunds`
  - `GET /api/gateways` (perfil + estado del breaker)
  - `GET/PUT/DELETE /api/chaos/{gateway}`
  - `PUT /api/gateways/{id}/enabled`
  - SSE `/api/events/stream`
  - Los errores son ProblemDetail con `code`.
- **Por hacer:** la traza de «qué puertos y adaptadores se han tocado» todavía no existe en el backend. Hay que añadirla en bootstrap como decoradores de los puertos que emiten eventos al stream, sin tocar el dominio ni la aplicación.
- **Demo pública:** el estado es compartido entre visitantes (el caos de uno lo ve otro). Hace falta un botón para restaurar todo.
- **Terminología fija:** pasarela (PSP), puerto, adaptador, dominio, failover, circuit breaker (CLOSED, OPEN, HALF_OPEN), idempotencia.

## Brand Commitments

- Texto en español de España.
- Nombre: PayAF.
- Forma parte del portfolio de Pedro Gómez, que no impone ningún estilo de referencia.

## Evidence on Hand

- Código y tests: repo público https://github.com/CODIGOAFRX/PayAF (unos 1000 tests).
- No hay clientes, testimonios ni cifras de negocio reales. No se inventan.

## Product Principles

1. Se entiende tocando: la primera acción útil está a un clic, sin tutorial.
2. Mostrar el mecanismo, no adornarlo: cada animación o color cuenta algo que ha pasado en el backend.
3. El dominio es el protagonista: los adaptadores cambian, el núcleo no.
4. Honestidad técnica: todo lo que se ve sale de la API real, nada es decorado.
5. Se puede restaurar: un visitante nunca deja la demo rota para el siguiente.

## Accessibility & Inclusion

WCAG 2.2 AA. El estado no se comunica solo por color (OPEN, CLOSED y HALF_OPEN llevan texto). Hay que respetar `prefers-reduced-motion`.
