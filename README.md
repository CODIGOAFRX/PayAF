# PayAF: Smart Payment Router

PayAF es un enrutador de pagos multi-PSP con failover: recibe una orden de pago, elige
la pasarela (PSP) más adecuada y, si falla, reintenta con otra. Es un proyecto para
aprender **arquitectura hexagonal** (puertos y adaptadores) con Java 21 y Spring Boot.

## Arquitectura

Las dependencias apuntan siempre hacia dentro. El dominio no conoce a nadie, la
aplicación solo conoce el dominio y los adaptadores solo conocen la aplicación.
`payaf-bootstrap` es el único módulo que usa Spring para cablearlo todo.

```
          Adaptadores de entrada (driving)            Adaptadores de salida (driven)
   +--------------------------------------+    +-----------------------------------------+
   |  in-rest   in-webhook   in-cli       |    |  out-gateway-sim   out-persistence-jpa  |
   +------------------+-------------------+    |  out-persistence-memory   out-outbox    |
                      |                        +--------------------+--------------------+
                      | llama a                                     | implementa
                      v                                             v
   +-----------------------------------------------------------------------------------+
   |  payaf-application                                                                |
   |     port.in  (casos de uso)      service (implementación)      port.out           |
   |                                                                                   |
   |      +-------------------------------------------------------------------+        |
   |      |  payaf-domain  (Java puro: entidades, objetos de valor, reglas)   |        |
   |      +-------------------------------------------------------------------+        |
   +-----------------------------------------------------------------------------------+

   payaf-bootstrap (Spring Boot) depende de todo y lo cablea.
```

Reglas que hace cumplir ArchUnit (`payaf-bootstrap/src/test/java/.../architecture/HexagonalArchitectureTest.java`):

- El dominio no depende de Spring, Jakarta, JPA ni Jackson, ni de la aplicación ni de los adaptadores.
- La aplicación no depende de los adaptadores ni de Spring.
- Ningún adaptador depende de otro.

## Módulos

| Módulo | Papel |
|---|---|
| `payaf-domain` | Núcleo del dominio, sin dependencias de producción |
| `payaf-application` | Puertos (`port.in`, `port.out`) y casos de uso (`service`) |
| `payaf-adapters/payaf-adapter-in-*` | Adaptadores de entrada: REST, webhooks de los PSP, CLI |
| `payaf-adapters/payaf-adapter-out-*` | Adaptadores de salida: simulador de PSP, JPA, memoria, outbox |
| `payaf-bootstrap` | Aplicación Spring Boot (`PayafApplication`) |

## Requisitos

- Java 21
- Docker (solo para el perfil `postgres`)

No hace falta instalar Maven: el proyecto trae el Maven Wrapper (`mvnw` / `mvnw.cmd`).

## Compilar y pasar los tests

```bash
./mvnw verify          # Linux, macOS, Git Bash
.\mvnw.cmd verify      # Windows (PowerShell)
```

## Arrancar

### Perfil `memory` (por defecto, sin base de datos)

```bash
./mvnw install -DskipTests
./mvnw spring-boot:run -pl payaf-bootstrap
```

El perfil `memory` es el de por defecto; para indicarlo de forma explícita:
`./mvnw spring-boot:run -pl payaf-bootstrap -Dspring-boot.run.profiles=memory`.

### Perfil `postgres`

```bash
docker compose up -d
./mvnw spring-boot:run -pl payaf-bootstrap -Dspring-boot.run.profiles=postgres
```

La conexión por defecto es `jdbc:postgresql://localhost:5433/payaf` (usuario y
contraseña `payaf`). Se puede cambiar con las variables `PAYAF_DB_URL`,
`PAYAF_DB_USER` y `PAYAF_DB_PASSWORD`.

### Perfiles

| Perfil | Persistencia | Outbox | Para qué |
|---|---|---|---|
| `memory` (por defecto) | `persistence-memory` (mapas, sin rollback) | cola en memoria del `InMemoryEventPublisher` | demo y tests rápidos, sin Docker |
| `postgres` | `persistence-jpa` (JPA + Flyway, transacciones reales) | tabla `outbox`, escrita en la misma transacción | lo que se despliega |

`ApplicationConfig` es el mismo en los dos: los casos de uso reciben puertos y no saben qué
perfil está activo. En los dos, un relay (`OutboxRelayScheduler`, cada 500 ms) lee el outbox y
entrega los eventos al broadcaster en proceso (`payaf-adapter-out-outbox`), del que bebe el SSE.

Propiedades propias: `payaf.reaper.enabled`, `payaf.outbox.relay.enabled`,
`payaf.sim.webhooks.enabled` (todas `true` por defecto) y los secretos de los webhooks
`payaf.webhooks.secrets.{stripe,adyen,redsys}` (variables `PAYAF_STRIPE_WEBHOOK_SECRET`,
`PAYAF_ADYEN_HMAC_KEY` y `PAYAF_REDSYS_SECRET`).

## API REST

Todo cuelga de `/api`. Los cuerpos son DTO propios (`ApiDtos`), nunca el dominio. Los importes
viajan como decimal en texto (`"12.34"`) para no perder céntimos. Hay un `requests.http` en la
raíz con el flujo completo (autorizar, replay, capturar, reembolsar, tumbar redsys y ver el failover).

```bash
# Autorizar: Idempotency-Key obligatoria. 201 con el pago, el enrutado (candidatas con comisión
# y descartes con motivo) y los intentos
curl -i -X POST http://localhost:8080/api/payments \
  -H 'Idempotency-Key: pedido-1001' -H 'Content-Type: application/json' \
  -d '{"amount":"40.00","currency":"EUR","country":"ES","merchantReference":"pedido-1001"}'
# Repetir lo mismo: 200 con la cabecera Idempotent-Replayed: true

curl -X POST http://localhost:8080/api/payments/<id>/capture
curl -X POST http://localhost:8080/api/payments/<id>/cancel
curl -X POST http://localhost:8080/api/payments/<id>/refunds -H 'Content-Type: application/json' -d '{"amount":"15.00"}'
curl http://localhost:8080/api/payments/<id>
curl 'http://localhost:8080/api/payments?limit=20'
curl http://localhost:8080/api/gateways          # perfil y estado del circuit breaker de cada PSP
curl -N http://localhost:8080/api/events/stream  # eventos en directo (SSE)

# Averías para el panel (administración del simulador, no un caso de uso de la aplicación)
curl -X PUT http://localhost:8080/api/chaos/redsys -H 'Content-Type: application/json' -d '{"down":true}'
curl -X PUT http://localhost:8080/api/chaos/stripe -H 'Content-Type: application/json' -d '{"latencyMs":3000}'
curl -X DELETE http://localhost:8080/api/chaos/redsys
curl -X PUT http://localhost:8080/api/gateways/mock/enabled -H 'Content-Type: application/json' -d '{"enabled":false}'
```

Los errores son `application/problem+json` (RFC 9457) con un `code` estable:

| `code` | HTTP |
|---|---|
| `INVALID_VALUE`, `ILLEGAL_TRANSITION` y demás reglas del dominio (`REFUND_EXCEEDS_CAPTURED`...) | 422 |
| `PAYMENT_NOT_FOUND`, `GATEWAY_NOT_FOUND` | 404 |
| `IDEMPOTENCY_CONFLICT`, `IDEMPOTENCY_IN_PROGRESS`, `CONCURRENT_MODIFICATION` | 409 |
| `GATEWAY_OPERATION_FAILED` | 502 |
| `OPERATION_UNRECORDED`, `AUTHORIZATION_UNRECORDED`, `INCONSISTENT_STATE` | 500 |
| `INVALID_BODY`, `MISSING_HEADER` | 400 |

Dos interfaces de la REST no son puertos de la aplicación: `EventFeed` (de dónde salen los
eventos del SSE) y `ChaosAdmin` (la consola del simulador). La REST no puede importar el outbox ni
el simulador, porque un adaptador nunca importa otro, así que declara lo que necesita y bootstrap
lo implementa con un puente (`EventStreamConfig`, `ChaosAdminBridge`).

## Webhooks

`POST /webhooks/{gateway}` recibe las notificaciones asíncronas de las PSP. Primero se verifica la
firma, con el formato propio de cada una, y solo si es buena llega a la aplicación:

| PSP | Firma |
|---|---|
| `stripe` | cabecera `Stripe-Signature: t=<seg>,v1=<hex>`, con `v1 = HMAC-SHA256(secreto, t + "." + cuerpo)` y 5 minutos de tolerancia |
| `adyen` | `additionalData.hmacSignature` de cada elemento: HMAC-SHA256 en Base64 de `pspReference:originalReference:merchantAccountCode:merchantReference:value:currency:eventCode:success` |
| `redsys` | formulario con `Ds_MerchantParameters` (JSON en Base64) y `Ds_Signature = HMAC-SHA256(Ds_MerchantParameters)` en Base64 URL-safe |

Respuestas: firma inválida, 401; contenido ilegible, 400; `APPLIED`, `DUPLICATE`,
`ALREADY_IN_STATE` y `UNKNOWN_PAYMENT`, 200 (las PSP solo reintentan si hay error); una
excepción de dominio o un conflicto de versión, 409, para que la PSP reintente.

El simulador emite webhooks de verdad: tras cada captura y reembolso aprobados en Stripe, Adyen o
Redsys, deja una notificación firmada y bootstrap la manda por HTTP a `localhost` con 1-2 s de
retraso (`payaf.sim.webhooks.enabled`). Como la operación ya se apuntó con la respuesta síncrona,
la respuesta normal es `ALREADY_IN_STATE`. El HMAC está **duplicado a propósito**: el simulador lo
implementa a mano (RFC 2104 sobre `MessageDigest`) y el adaptador de webhooks con `javax.crypto.Mac`.
Son dos sistemas que solo comparten un contrato, como Stripe y nosotros; si compartieran código,
un fallo firmaría y verificaría igual de mal sin que ningún test lo notara.

## CLI

`payaf-adapter-in-cli` (picocli) usa **los mismos puertos de entrada que la API REST**
(`AuthorizePaymentUseCase`, `CapturePaymentUseCase`...). Es el punto didáctico: dos adaptadores
driving muy distintos, HTTP con JSON y una terminal con texto, y un solo núcleo que no sabe quién
lo llama. Ninguna regla de negocio se repite en la CLI.

Se activa sin servidor web y con el argumento `cli`. Lo que va antes de `cli` es para Spring y lo
que va después, para picocli (lo lanza un `CommandLineRunner` de `CliConfig`):

```bash
./mvnw install -DskipTests
JAR=payaf-bootstrap/target/payaf-bootstrap-0.1.0-SNAPSHOT.jar
OPTS="--spring.main.web-application-type=none --spring.profiles.active=postgres --spring.main.banner-mode=off --logging.level.root=WARN"

java -jar $JAR $OPTS cli authorize --amount 12.34 --currency EUR --country ES --ref X --key K
java -jar $JAR $OPTS cli capture <id>
java -jar $JAR $OPTS cli refund <id> --amount 2.00
java -jar $JAR $OPTS cli get <id>
java -jar $JAR $OPTS cli gateways
```

Sale con 0 si va bien, 1 si el núcleo rechaza la operación (imprime su `code`) y 2 si el comando
está mal escrito. Con el perfil `memory` cada ejecución empieza vacía, así que para encadenar
comandos hay que usar `postgres`.

## Pasarelas simuladas

Los dos perfiles enrutan contra cuatro PSP simuladas (`payaf-adapter-out-gateway-sim`). Cada
una imita el «idioma» de la de verdad (PaymentIntents `pi_...` de Stripe, `pspReference` de 16
dígitos de Adyen, `Ds_Order` y `Ds_Response` de Redsys) y su adaptador lo traduce al puerto
`PaymentGateway`: es una capa anticorrupción.

| Pasarela | Divisas | Países | Comisión |
|---|---|---|---|
| `stripe` | EUR, USD, GBP | todos | 2,90 % + 0,25 |
| `adyen` | EUR, USD, GBP, JPY | todos | 2,50 % + 0,30 (JPY sin fija) |
| `redsys` | EUR | ES | 1,20 % |
| `mock` | todas | todos | 9,00 % |

**Rechazo determinista.** Todas rechazan (DECLINED) los importes cuyas unidades menores
terminan en 51 (`amountMinor % 100 == 51`, por ejemplo 100,51 EUR), cada una con su código
nativo: Stripe `insufficient_funds` (decline code de `card_declined`), Adyen
`Not enough balance` (`Refused`), Redsys `0190` y mock `mock_decline_51`. Un rechazo no hace
failover: el pago queda FAILED con `DECLINED: <código>`.

**Operaciones que la PSP no admite** (capturar dos veces, anular algo capturado, reembolsar de
más, referencia desconocida, importe que no cuadra) son DECLINED con el código nativo, no
ERROR: la PSP funciona y dice que no, así que no cuenta para el circuit breaker.

**Averías.** `GatewayChaosControl` (la API de administración del simulador, no un puerto de
la aplicación) permite tirar una pasarela (ERROR `HTTP_503`), darle latencia (si supera el
timeout de 2 s, TIMEOUT) o una tasa de error (ERROR `HTTP_500`).

**Pagos huérfanos.** Cada 30 s un reaper recoge los pagos que llevan más de 2 minutos en
PENDING: si alguna PSP llegó a aprobarlos los autoriza, y si no los da por abandonados
(`FAILED` con `ABANDONED`). Se apaga con `payaf.reaper.enabled=false`.

### Comprobar que está vivo

```bash
curl http://localhost:8080/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}
```
