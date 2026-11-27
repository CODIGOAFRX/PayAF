package dev.pedrogomez.payaf.adapter.in.rest;

/**
 * Administración de averías y habilitación de pasarelas para el panel
 * ({@code /api/chaos/{gateway}} y {@code PUT /api/gateways/{id}/enabled}).
 * <p>
 * <strong>NO es un puerto de la aplicación.</strong> La aplicación no sabe que sus pasarelas son
 * simuladas ni tiene casos de uso para romperlas: esto es la consola del simulador
 * ({@code GatewayChaosControl} y {@code SimulatedGatewayCatalog} de {@code adapter.out.gatewaysim}).
 * Como la REST no puede importar otro adaptador, declara aquí lo que necesita, con sus propios
 * tipos, y bootstrap la implementa delegando en el simulador. Con PSP reales, esta interfaz y sus
 * endpoints desaparecerían sin tocar la aplicación.
 */
public interface ChaosAdmin {

    /** @throws UnknownGatewayException si no hay una pasarela simulada con ese identificador */
    ChaosSettings get(String gateway);

    /**
     * @throws UnknownGatewayException  si la pasarela no existe
     * @throws IllegalArgumentException si los valores no son válidos (latencia negativa, tasa fuera de [0, 1])
     */
    ChaosSettings set(String gateway, ChaosSettings settings);

    /** Quita las averías. @throws UnknownGatewayException si la pasarela no existe */
    ChaosSettings reset(String gateway);

    /** Saca o vuelve a meter la pasarela en el enrutado. @throws UnknownGatewayException si no existe */
    void setEnabled(String gateway, boolean enabled);

    /**
     * Averías de una pasarela.
     *
     * @param down      caída (HTTP 503)
     * @param latencyMs latencia añadida a cada llamada, en milisegundos
     * @param errorRate probabilidad de 0 a 1 de un HTTP 500
     */
    record ChaosSettings(boolean down, long latencyMs, double errorRate) {
    }

    /** La pasarela no existe en el simulador. */
    final class UnknownGatewayException extends RuntimeException {

        public UnknownGatewayException(String gateway) {
            super("No hay ninguna pasarela " + gateway);
        }
    }
}
