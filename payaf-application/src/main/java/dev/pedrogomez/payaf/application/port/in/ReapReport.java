package dev.pedrogomez.payaf.application.port.in;

/**
 * Resultado de una pasada del reaper de pagos huérfanos.
 *
 * @param abandoned pagos PENDING sin autorización aprobada que han pasado a FAILED (ABANDONED)
 * @param recovered pagos PENDING con una autorización aprobada en la PSP que han pasado a AUTHORIZED
 */
public record ReapReport(int abandoned, int recovered) {

    public ReapReport {
        if (abandoned < 0 || recovered < 0) {
            throw new IllegalArgumentException("Los contadores del reaper no pueden ser negativos");
        }
    }

    /** Total de pagos resueltos en la pasada. */
    public int total() {
        return abandoned + recovered;
    }
}
