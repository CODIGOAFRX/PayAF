package dev.pedrogomez.payaf.adapter.out.persistencejpa;

import dev.pedrogomez.payaf.application.port.out.CircuitBreakerStore;
import dev.pedrogomez.payaf.domain.routing.CircuitBreaker;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerMemento;
import dev.pedrogomez.payaf.domain.routing.CircuitState;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link CircuitBreakerStore} que guarda el estado de cada breaker en {@code circuit_breakers}
 * (serializado en JSON) y mantiene una caché en memoria del proceso.
 * <p>
 * <strong>Por qué una caché.</strong> El contrato del puerto exige devolver siempre la misma
 * instancia por pasarela, porque los servicios serializan los cambios sincronizándose sobre ella.
 * Así que la instancia buena es la de memoria, y la tabla es su copia duradera:
 * <ul>
 *   <li>al construirse (al arrancar la aplicación) carga todos los breakers guardados con
 *       {@link CircuitBreaker#restore}, aplicándoles la configuración vigente: un reinicio no
 *       vuelve a mandar tráfico a una pasarela que estaba abierta;</li>
 *   <li>cada {@link #save} escribe el estado con un {@code INSERT ... ON CONFLICT DO UPDATE}. No se
 *       llama dentro de la transacción de un pago, así que cada guardado se confirma solo.</li>
 * </ul>
 * <strong>Limitación con varias réplicas.</strong> Cada réplica tendría su propia caché y, por
 * tanto, su propio breaker: una podría abrir el circuito y otra seguir mandando tráfico, y la
 * tabla tendría el último estado que escribiera cualquiera de ellas. Para la demo (una réplica)
 * es aceptable; con varias habría que leer y escribir el estado en la base de datos en cada
 * decisión, con bloqueo optimista, o compartirlo en algo como Redis.
 */
public final class JdbcCircuitBreakerStore implements CircuitBreakerStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CircuitBreakerConfig config;
    private final ConcurrentMap<GatewayId, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    /** Carga los breakers guardados. */
    public JdbcCircuitBreakerStore(JdbcTemplate jdbc, ObjectMapper json, CircuitBreakerConfig config) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
        this.config = Objects.requireNonNull(config);
        jdbc.query("SELECT gateway, state::text AS state FROM circuit_breakers", rs -> {
            GatewayId gateway = GatewayId.of(rs.getString("gateway"));
            breakers.put(gateway, CircuitBreaker.restore(gateway, config, fromJson(rs.getString("state"))));
        });
    }

    @Override
    public CircuitBreaker get(GatewayId gateway) {
        Objects.requireNonNull(gateway, "la pasarela no puede ser nula");
        return breakers.computeIfAbsent(gateway, id -> new CircuitBreaker(id, config));
    }

    /**
     * @throws IllegalArgumentException si se intenta guardar otra instancia distinta de la que ya
     *                                  hay para esa pasarela
     */
    @Override
    public void save(CircuitBreaker breaker) {
        Objects.requireNonNull(breaker, "el breaker no puede ser nulo");
        CircuitBreaker known = breakers.putIfAbsent(breaker.gateway(), breaker);
        if (known != null && known != breaker) {
            throw new IllegalArgumentException("Ya hay otra instancia del breaker de " + breaker.gateway());
        }
        jdbc.update("""
                        INSERT INTO circuit_breakers (gateway, state, updated_at) VALUES (?, ?::jsonb, ?)
                        ON CONFLICT (gateway) DO UPDATE SET state = EXCLUDED.state, updated_at = EXCLUDED.updated_at""",
                breaker.gateway().value(), toJson(breaker.memento()), Timestamp.from(Instant.now()));
    }

    private String toJson(CircuitBreakerMemento memento) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", memento.state().name());
        m.put("consecutiveFailures", memento.consecutiveFailures());
        m.put("openedAt", memento.openedAt().map(Instant::toString).orElse(null));
        m.put("trialsAcquiredAt", memento.trialsAcquiredAt().stream().map(Instant::toString).toList());
        return json.writeValueAsString(m);
    }

    private CircuitBreakerMemento fromJson(String text) {
        JsonNode node = json.readTree(text);
        JsonNode openedAt = node.get("openedAt");
        List<Instant> trials = node.get("trialsAcquiredAt").valueStream()
                .map(t -> Instant.parse(t.asString()))
                .toList();
        return new CircuitBreakerMemento(CircuitState.valueOf(node.get("state").asString()),
                node.get("consecutiveFailures").asInt(),
                openedAt == null || openedAt.isNull() ? Optional.empty() : Optional.of(Instant.parse(openedAt.asString())),
                trials);
    }
}
