package dev.pedrogomez.payaf.domain.shared;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Importe de dinero en una divisa, guardado en unidades menores (céntimos, peniques...).
 * <p>
 * Es dominio porque el dinero tiene reglas propias que no son las de un número:
 * <ul>
 *   <li>Se guarda como {@code long} de unidades menores, nunca como {@code double}
 *       (0,1 + 0,2 no da 0,3) ni como {@code BigDecimal} (admitiría fracciones de céntimo
 *       que no existen). Así un importe siempre es representable y exacto.</li>
 *   <li>No se mezclan divisas: operar EUR con USD lanza {@link CurrencyMismatchException}.</li>
 *   <li>El desbordamiento no se trunca en silencio: {@link Math#addExact} y compañía
 *       lanzan {@link ArithmeticException}, porque un importe que da la vuelta sería
 *       un error gravísimo en un sistema de pagos.</li>
 *   <li>El redondeo de comisiones es explícito y documentado (HALF_UP), no el que salga.</li>
 * </ul>
 * El importe puede ser negativo o cero (por ejemplo, el resultado de una resta); son
 * los agregados quienes exigen importes positivos donde el negocio lo pide.
 *
 * @param amountMinor importe en unidades menores de la divisa
 * @param currency    divisa del importe
 */
public record Money(long amountMinor, Currency currency) {

    private static final long BASIS_POINTS_PER_UNIT = 10_000L;

    private static final Pattern DECIMAL = Pattern.compile("(-)?([0-9]+)(?:[.]([0-9]+))?");

    public Money {
        Objects.requireNonNull(currency, "la divisa no puede ser nula");
    }

    /** Importe a partir de unidades menores: {@code of(1234, EUR)} son 12,34 EUR. */
    public static Money of(long amountMinor, Currency currency) {
        return new Money(amountMinor, currency);
    }

    /** Importe cero en la divisa indicada. */
    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    /**
     * Importe a partir de su representación decimal con punto: {@code parse("12.34", EUR)}.
     * <p>
     * Rechaza más decimales de los que admite la divisa ({@code "12.345"} en EUR o
     * {@code "12.5"} en JPY) en lugar de redondear: si alguien pide cobrar fracciones de
     * céntimo, el error está en la petición y hay que decírselo, no adivinar.
     *
     * @throws InvalidValueException si el texto no es un decimal válido o tiene demasiados decimales
     * @throws ArithmeticException   si el importe no cabe en un {@code long} de unidades menores
     */
    public static Money parse(String decimal, Currency currency) {
        Objects.requireNonNull(decimal, "el importe no puede ser nulo");
        Objects.requireNonNull(currency, "la divisa no puede ser nula");
        Matcher matcher = DECIMAL.matcher(decimal);
        if (!matcher.matches()) {
            throw new InvalidValueException("Importe no válido: «" + decimal + "»");
        }
        boolean negative = matcher.group(1) != null;
        String integerPart = matcher.group(2);
        String fractionPart = matcher.group(3) == null ? "" : matcher.group(3);
        if (fractionPart.length() > currency.minorDigits()) {
            throw new InvalidValueException("El importe «" + decimal + "» tiene más decimales de los que admite "
                    + currency + " (" + currency.minorDigits() + ")");
        }
        String paddedFraction = fractionPart + "0".repeat(currency.minorDigits() - fractionPart.length());
        // Se parsea con signo, y no el valor absoluto para negarlo después: el rango de long no
        // es simétrico y Long.MIN_VALUE no tiene opuesto positivo.
        long major = parseDigits((negative ? "-" : "") + integerPart, decimal);
        long minor = paddedFraction.isEmpty() ? 0 : parseDigits(paddedFraction, decimal);
        long scaled = Math.multiplyExact(major, currency.minorUnitsPerMajor());
        long amount = negative ? Math.subtractExact(scaled, minor) : Math.addExact(scaled, minor);
        return new Money(amount, currency);
    }

    private static long parseDigits(String digits, String original) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            throw new ArithmeticException("El importe «" + original + "» no cabe en unidades menores");
        }
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency);
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amountMinor > other.amountMinor;
    }

    public boolean isZero() {
        return amountMinor == 0;
    }

    public boolean isPositive() {
        return amountMinor > 0;
    }

    /**
     * Porcentaje del importe expresado en puntos básicos (1 pb = 0,01 %; 10.000 pb = 100 %),
     * redondeado HALF_UP a la unidad menor: las mitades se alejan del cero.
     * <p>
     * Es la forma de calcular comisiones. Se hace todo con aritmética entera
     * ({@code importe × pb / 10.000}) para que el resultado sea exacto y reproducible:
     * con {@code double}, 2,5 céntimos podría acabar siendo 2,4999... y redondearse mal.
     * Ejemplo: el 2,9 % (290 pb) de 10,05 EUR son 29,145 céntimos, que se redondean a 29.
     * <p>
     * Para no desbordar en un producto intermedio cuando el resultado sí cabe, el importe se
     * parte en {@code q = importe / 10.000} y {@code r = importe % 10.000}, y se calcula
     * {@code q × pb + redondeo(r × pb / 10.000)}. Es exacto porque
     * {@code importe × pb / 10.000 = q × pb + r × pb / 10.000} y {@code q × pb} es entero; como
     * {@code q} y {@code r} tienen el mismo signo, redondear solo la parte fraccionaria
     * alejándola del cero da el mismo resultado que redondear el total. Así el 100 % de
     * cualquier importe es el propio importe.
     *
     * @param basisPoints puntos básicos, mayor o igual que cero
     * @throws InvalidValueException si los puntos básicos son negativos
     * @throws ArithmeticException   si el resultado no cabe en un {@code long}
     */
    public Money percentage(int basisPoints) {
        if (basisPoints < 0) {
            throw new InvalidValueException("Los puntos básicos no pueden ser negativos: " + basisPoints);
        }
        long quotient = amountMinor / BASIS_POINTS_PER_UNIT;
        long remainder = amountMinor % BASIS_POINTS_PER_UNIT;
        // |r × pb| < 10.000 × 2^31, así que este producto nunca desborda.
        long fractional = roundHalfUp(remainder * basisPoints, BASIS_POINTS_PER_UNIT);
        long result = Math.addExact(Math.multiplyExact(quotient, (long) basisPoints), fractional);
        return new Money(result, currency);
    }

    /** {@code dividend / divisor} redondeado HALF_UP (las mitades se alejan del cero); divisor positivo. */
    private static long roundHalfUp(long dividend, long divisor) {
        long quotient = dividend / divisor;
        long remainder = dividend % divisor;
        if (Math.abs(remainder) * 2 >= divisor) {
            quotient += Long.signum(dividend);
        }
        return quotient;
    }

    /** Representación decimal con punto, la inversa de {@link #parse}: {@code "12.34"}, {@code "1500"}. */
    public String toDecimalString() {
        int digits = currency.minorDigits();
        if (digits == 0) {
            return Long.toString(amountMinor);
        }
        String sign = amountMinor < 0 ? "-" : "";
        String absolute = Long.toString(amountMinor).replace("-", "");
        if (absolute.length() <= digits) {
            absolute = "0".repeat(digits - absolute.length() + 1) + absolute;
        }
        int split = absolute.length() - digits;
        return sign + absolute.substring(0, split) + "." + absolute.substring(split);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "el importe no puede ser nulo");
        if (currency != other.currency) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public String toString() {
        return toDecimalString() + " " + currency;
    }
}
