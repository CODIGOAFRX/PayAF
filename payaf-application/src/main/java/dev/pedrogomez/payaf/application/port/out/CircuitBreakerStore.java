package dev.pedrogomez.payaf.application.port.out;

import dev.pedrogomez.payaf.domain.routing.CircuitBreaker;
import dev.pedrogomez.payaf.domain.shared.GatewayId;

/**
 * Puerto de salida (driven): dónde vive el circuit breaker de cada pasarela.
 * <p>
 * <strong>El estado del breaker es compartido entre peticiones.</strong> No pertenece a
 * ningún pago: es la memoria colectiva de cómo le va a una pasarela. Si cada petición
 * empezara con un breaker nuevo, nunca se abriría. Por eso hace falta un almacén, y por eso
 * es un puerto: en memoria basta con un mapa; con varias instancias de la aplicación hará
 * falta guardarlo en un sitio común.
 * <p>
 * Contrato:
 * <ul>
 *   <li>{@link #get} devuelve el breaker de la pasarela y, si no existe, lo crea cerrado con
 *       la {@code CircuitBreakerConfig} que tenga configurada el adaptador.</li>
 *   <li>El adaptador en memoria debe devolver siempre la misma instancia para una pasarela:
 *       {@link CircuitBreaker} no es seguro para hilos, y los servicios serializan sus
 *       cambios sincronizándose sobre esa instancia.</li>
 *   <li>{@link #save} guarda el estado tras un cambio. Un adaptador persistente debe evitar
 *       que dos peticiones se pisen el estado (bloqueo optimista, por ejemplo).</li>
 * </ul>
 * No se llama dentro de una transacción de pagos: se actualiza justo después de cada
 * llamada a la pasarela, que siempre ocurre fuera de transacción.
 */
public interface CircuitBreakerStore {

    /** El breaker de la pasarela; lo crea con la configuración si no existe. */
    CircuitBreaker get(GatewayId gateway);

    void save(CircuitBreaker breaker);
}
