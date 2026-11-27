package dev.pedrogomez.payaf.adapter.out.gatewaysim;

import java.time.Duration;

/**
 * Cómo se «espera» la latencia simulada. Se inyecta para que los tests no duerman de verdad:
 * les basta con apuntar cuánto se habría dormido.
 */
@FunctionalInterface
public interface Sleeper {

    /** Espera {@code duration}; con cero o negativa, no hace nada. */
    void sleep(Duration duration);

    /**
     * Duerme el hilo de verdad. Si lo interrumpen, deja de esperar, restaura el flag de
     * interrupción y vuelve sin lanzar: el adaptador nunca lanza excepciones (contrato de
     * {@code PaymentGateway}).
     */
    static Sleeper system() {
        return duration -> {
            if (duration.isNegative() || duration.isZero()) {
                return;
            }
            try {
                Thread.sleep(duration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }
}
