package dev.pedrogomez.payaf.adapter.out.gatewaysim;

/**
 * Regla común de las PSP simuladas para provocar un rechazo del emisor a voluntad: se rechaza
 * toda autorización cuyo importe en unidades menores termina en 51
 * ({@code amountMinor % 100 == 51}; por ejemplo 100,51 EUR o 151 JPY). Cada PSP lo devuelve con
 * su código nativo.
 */
public final class DeterministicDecline {

    /** Los dos últimos dígitos del importe que provocan el rechazo. */
    public static final int DECLINE_SUFFIX = 51;

    private DeterministicDecline() {
    }

    public static boolean declines(long amountMinor) {
        return Math.abs(amountMinor % 100) == DECLINE_SUFFIX;
    }
}
