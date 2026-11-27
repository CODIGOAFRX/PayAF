package dev.pedrogomez.payaf.application.port.in;

import java.time.Instant;
import java.util.List;

/**
 * Puerto de entrada (driving): salud de las pasarelas para el panel.
 * <p>
 * Es una consulta pura: no hace avanzar los circuit breakers ni emite eventos. El
 * {@code now} sirve para enseñar el estado efectivo: un circuito OPEN cuyo tiempo de
 * apertura ya ha vencido se muestra como HALF_OPEN, que es lo que vería la próxima petición.
 */
public interface QueryGatewayHealthUseCase {

    /** Una vista por pasarela configurada, en el orden del catálogo. */
    List<GatewayHealthView> health(Instant now);
}
