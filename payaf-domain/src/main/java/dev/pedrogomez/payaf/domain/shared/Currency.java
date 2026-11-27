package dev.pedrogomez.payaf.domain.shared;

/**
 * Divisas que admite PayAF, con el número de decimales de su unidad menor (ISO 4217).
 * <p>
 * Es dominio porque los decimales de cada divisa son una regla del negocio de pagos:
 * un pago de 12,345 EUR no existe, y uno de 12,5 JPY tampoco. No se usa
 * {@link java.util.Currency} para que el conjunto de divisas sea cerrado y explícito:
 * añadir una divisa es una decisión de negocio, no algo que dependa del JDK.
 */
public enum Currency {

    EUR(2),
    USD(2),
    GBP(2),
    JPY(0);

    private final int minorDigits;

    Currency(int minorDigits) {
        this.minorDigits = minorDigits;
    }

    /** Decimales de la unidad menor: 2 para los céntimos del euro, 0 para el yen. */
    public int minorDigits() {
        return minorDigits;
    }

    /** Cuántas unidades menores forman una unidad mayor (100 para EUR, 1 para JPY). */
    public long minorUnitsPerMajor() {
        long factor = 1;
        for (int i = 0; i < minorDigits; i++) {
            factor *= 10;
        }
        return factor;
    }
}
