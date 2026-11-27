package dev.pedrogomez.payaf.domain.shared;

/**
 * Se ha intentado operar con importes de divisas distintas.
 * <p>
 * Es dominio porque sumar euros con dólares no es un error de programación cualquiera:
 * es una operación que el negocio prohíbe (haría falta una conversión con un tipo de
 * cambio, y eso sería otra regla explícita). Código estable: {@code "CURRENCY_MISMATCH"}.
 */
public final class CurrencyMismatchException extends DomainException {

    public static final String CODE = "CURRENCY_MISMATCH";

    private final Currency expected;
    private final Currency actual;

    public CurrencyMismatchException(Currency expected, Currency actual) {
        super(CODE, "Divisas distintas: se esperaba " + expected + " y llegó " + actual);
        this.expected = expected;
        this.actual = actual;
    }

    public Currency expected() {
        return expected;
    }

    public Currency actual() {
        return actual;
    }
}
