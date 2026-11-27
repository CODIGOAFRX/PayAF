package dev.pedrogomez.payaf.adapter.out.gatewaysim;

import java.util.Random;

/**
 * Generación de referencias aleatorias para las PSP simuladas, con el {@link Random} que se
 * inyecte (para que los tests sean reproducibles). Seguro para hilos aunque el {@code Random}
 * no lo sea.
 */
public final class References {

    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final String UPPER_ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String DIGITS = "0123456789";

    private References() {
    }

    /** {@code length} caracteres de [A-Za-z0-9]. */
    public static String alphanumeric(Random random, int length) {
        return from(ALPHANUMERIC, random, length);
    }

    /** {@code length} caracteres de [A-Z0-9]. */
    public static String upperAlphanumeric(Random random, int length) {
        return from(UPPER_ALPHANUMERIC, random, length);
    }

    /** {@code length} dígitos; el primero nunca es cero si {@code noLeadingZero}. */
    public static String digits(Random random, int length, boolean noLeadingZero) {
        synchronized (random) {
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                int bound = i == 0 && noLeadingZero ? 9 : 10;
                int offset = i == 0 && noLeadingZero ? 1 : 0;
                sb.append(DIGITS.charAt(offset + random.nextInt(bound)));
            }
            return sb.toString();
        }
    }

    private static String from(String alphabet, Random random, int length) {
        synchronized (random) {
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            return sb.toString();
        }
    }
}
