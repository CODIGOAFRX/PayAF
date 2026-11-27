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

### Comprobar que está vivo

```bash
curl http://localhost:8080/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}
```
