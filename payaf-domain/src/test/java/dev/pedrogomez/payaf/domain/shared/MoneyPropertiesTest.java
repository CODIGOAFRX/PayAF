package dev.pedrogomez.payaf.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.math.RoundingMode;
import java.math.BigDecimal;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * Propiedades de {@link Money} sobre todo el rango de {@code long}.
 * <p>
 * El oráculo del redondeo es {@link BigDecimal} con {@link RoundingMode#HALF_UP}, que es
 * exactamente la definición que promete el Javadoc (las mitades se alejan del cero). Así
 * no se compara la implementación consigo misma.
 */
class MoneyPropertiesTest {

    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    @Provide
    Arbitrary<Currency> currencies() {
        return Arbitraries.of(Currency.class);
    }

    // ---------------------------------------------------------------------------------
    // plus / minus
    // ---------------------------------------------------------------------------------

    @Property
    @Label("plus y minus son inversos cuando no hay desbordamiento")
    void plusAndMinusAreInverse(@ForAll long a, @ForAll long b, @ForAll("currencies") Currency currency) {
        Money x = Money.of(a, currency);
        Money y = Money.of(b, currency);
        BigInteger exactSum = BigInteger.valueOf(a).add(BigInteger.valueOf(b));
        if (fitsInLong(exactSum)) {
            assertThat(x.plus(y).minus(y)).isEqualTo(x);
            assertThat(x.plus(y)).isEqualTo(y.plus(x));
        } else {
            assertThatThrownBy(() -> x.plus(y)).isInstanceOf(ArithmeticException.class);
        }
        BigInteger exactDiff = BigInteger.valueOf(a).subtract(BigInteger.valueOf(b));
        if (fitsInLong(exactDiff)) {
            assertThat(x.minus(y).plus(y)).isEqualTo(x);
        } else {
            assertThatThrownBy(() -> x.minus(y)).isInstanceOf(ArithmeticException.class);
        }
    }

    @Property
    @Label("operar con divisas distintas siempre lanza CurrencyMismatchException, con las dos divisas")
    void mixingCurrenciesAlwaysFails(@ForAll long a, @ForAll long b,
                                     @ForAll("currencies") Currency c1, @ForAll("currencies") Currency c2) {
        if (c1 == c2) {
            return;
        }
        Money x = Money.of(a, c1);
        Money y = Money.of(b, c2);
        assertThatThrownBy(() -> x.plus(y)).isInstanceOfSatisfying(CurrencyMismatchException.class, e -> {
            assertThat(e.expected()).isEqualTo(c1);
            assertThat(e.actual()).isEqualTo(c2);
            assertThat(e.code()).isEqualTo("CURRENCY_MISMATCH");
        });
        assertThatThrownBy(() -> x.minus(y)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> x.isGreaterThan(y)).isInstanceOf(CurrencyMismatchException.class);
    }

    // ---------------------------------------------------------------------------------
    // percentage
    // ---------------------------------------------------------------------------------

    @Property
    @Label("percentage(0) es cero para cualquier importe")
    void zeroBasisPointsIsZero(@ForAll long a, @ForAll("currencies") Currency currency) {
        assertThat(Money.of(a, currency).percentage(0)).isEqualTo(Money.zero(currency));
    }

    /**
     * Especificación: {@code percentage(10000) == self}. El resultado siempre cabe en un
     * {@code long} (es el propio importe), así que no hay desbordamiento que justifique
     * una excepción.
     */
    @Property
    @Label("percentage(10000) devuelve el mismo importe en todo el rango de long")
    void fullPercentageIsIdentity(@ForAll long a, @ForAll("currencies") Currency currency) {
        Money money = Money.of(a, currency);
        assertThat(money.percentage(10_000)).isEqualTo(money);
    }

    @Property
    @Label("percentage(10000) devuelve el mismo importe en el rango sin desbordamiento intermedio")
    void fullPercentageIsIdentityInSafeRange(
            @ForAll @LongRange(min = -922_337_203_685_477L, max = 922_337_203_685_477L) long a) {
        Money money = Money.of(a, Currency.EUR);
        assertThat(money.percentage(10_000)).isEqualTo(money);
    }

    @Property
    @Label("percentage redondea HALF_UP igual que BigDecimal cuando el resultado cabe")
    void percentageMatchesBigDecimalHalfUp(
            @ForAll @LongRange(min = -922_337_203_685_477L, max = 922_337_203_685_477L) long a,
            @ForAll @IntRange(min = 0, max = 10_000) int bps) {
        BigDecimal exact = BigDecimal.valueOf(a).multiply(BigDecimal.valueOf(bps))
                .divide(BigDecimal.valueOf(10_000));
        long expected = exact.setScale(0, RoundingMode.HALF_UP).longValueExact();
        assertThat(Money.of(a, Currency.EUR).percentage(bps).amountMinor()).isEqualTo(expected);
    }

    @Property
    @Label("percentage redondea HALF_UP en los importes pequeños, donde están todas las mitades")
    void percentageHalfUpOnSmallAmounts(@ForAll @LongRange(min = -20_000, max = 20_000) long a,
                                        @ForAll @IntRange(min = 0, max = 10_000) int bps) {
        long expected = BigDecimal.valueOf(a).multiply(BigDecimal.valueOf(bps))
                .divide(BigDecimal.valueOf(10_000)).setScale(0, RoundingMode.HALF_UP).longValueExact();
        assertThat(Money.of(a, Currency.GBP).percentage(bps).amountMinor()).isEqualTo(expected);
    }

    @Property
    @Label("percentage es simétrico respecto al signo: f(-x) == -f(x)")
    void percentageIsOddFunction(
            @ForAll @LongRange(min = -900_000_000_000_000L, max = 900_000_000_000_000L) long a,
            @ForAll @IntRange(min = 0, max = 10_000) int bps) {
        long positive = Money.of(a, Currency.USD).percentage(bps).amountMinor();
        long negative = Money.of(-a, Currency.USD).percentage(bps).amountMinor();
        assertThat(negative).isEqualTo(-positive);
    }

    @Property
    @Label("percentage con puntos básicos negativos se rechaza")
    void negativeBasisPointsRejected(@ForAll long a, @ForAll @IntRange(min = Integer.MIN_VALUE, max = -1) int bps) {
        assertThatThrownBy(() -> Money.of(a, Currency.EUR).percentage(bps))
                .isInstanceOf(InvalidValueException.class);
    }

    @Example
    @Label("mitades exactas: 1 céntimo al 50 % → 1; 3 céntimos al 50 % → 2; y sus negativos")
    void halvesRoundAwayFromZero() {
        assertThat(Money.of(1, Currency.EUR).percentage(5_000)).isEqualTo(Money.of(1, Currency.EUR));
        assertThat(Money.of(3, Currency.EUR).percentage(5_000)).isEqualTo(Money.of(2, Currency.EUR));
        assertThat(Money.of(-1, Currency.EUR).percentage(5_000)).isEqualTo(Money.of(-1, Currency.EUR));
        assertThat(Money.of(-3, Currency.EUR).percentage(5_000)).isEqualTo(Money.of(-2, Currency.EUR));
        // Justo por debajo y por encima de la mitad
        assertThat(Money.of(1, Currency.EUR).percentage(4_999)).isEqualTo(Money.zero(Currency.EUR));
        assertThat(Money.of(-1, Currency.EUR).percentage(4_999)).isEqualTo(Money.zero(Currency.EUR));
        assertThat(Money.of(1, Currency.EUR).percentage(5_001)).isEqualTo(Money.of(1, Currency.EUR));
        // 0,5 yenes al 1 %: 50 yenes × 100 pb = 0,5 → 1 (el yen redondea igual)
        assertThat(Money.of(50, Currency.JPY).percentage(100)).isEqualTo(Money.of(1, Currency.JPY));
        assertThat(Money.of(49, Currency.JPY).percentage(100)).isEqualTo(Money.zero(Currency.JPY));
    }

    @Example
    @Label("percentage cuyo resultado cabe en long no debe lanzar ArithmeticException")
    void percentageDoesNotOverflowWhenResultFits() {
        // 1 % de Long.MAX_VALUE es ~9,2e16: cabe de sobra, aunque importe × pb no quepa.
        Money max = Money.of(Long.MAX_VALUE, Currency.JPY);
        assertThat(max.percentage(100).amountMinor())
                .isEqualTo(new BigDecimal(LONG_MAX).divide(BigDecimal.valueOf(100))
                        .setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    // ---------------------------------------------------------------------------------
    // parse / toDecimalString
    // ---------------------------------------------------------------------------------

    @Property
    @Label("parse(toDecimalString(x)) == x en todo el rango de long (es su inversa)")
    void decimalStringRoundTrips(@ForAll long a, @ForAll("currencies") Currency currency) {
        Money money = Money.of(a, currency);
        assertThat(Money.parse(money.toDecimalString(), currency)).isEqualTo(money);
    }

    @Property
    @Label("toDecimalString lleva exactamente los decimales de la divisa")
    void decimalStringHasCurrencyDigits(@ForAll long a, @ForAll("currencies") Currency currency) {
        String text = Money.of(a, currency).toDecimalString();
        if (currency.minorDigits() == 0) {
            assertThat(text).doesNotContain(".");
        } else {
            assertThat(text).matches("-?[0-9]+\\.[0-9]{" + currency.minorDigits() + "}");
        }
    }

    @Property
    @Label("parse rechaza cualquier decimal de más, sea cual sea la divisa")
    void parseRejectsExtraDecimals(@ForAll @LongRange(min = 0, max = 1_000_000) long major,
                                   @ForAll("currencies") Currency currency,
                                   @ForAll @IntRange(min = 1, max = 5) int extra) {
        String decimals = "1".repeat(currency.minorDigits() + extra);
        assertThatThrownBy(() -> Money.parse(major + "." + decimals, currency))
                .isInstanceOf(InvalidValueException.class);
    }

    // ---------------------------------------------------------------------------------
    // percentage tras el arreglo: todo el rango, negativos y bordes
    // ---------------------------------------------------------------------------------

    @Property(tries = 5_000)
    @Label("percentage = BigDecimal HALF_UP en todo el rango de long y de pb >= 0; ArithmeticException solo si el resultado no cabe")
    void percentageMatchesOracleEverywhere(@ForAll long a,
                                           @ForAll @IntRange(min = 0, max = Integer.MAX_VALUE) int bps) {
        BigInteger exact = new BigDecimal(a).multiply(BigDecimal.valueOf(bps))
                .divide(BigDecimal.valueOf(10_000)).setScale(0, RoundingMode.HALF_UP).toBigIntegerExact();
        Money money = Money.of(a, Currency.EUR);
        if (fitsInLong(exact)) {
            assertThat(money.percentage(bps).amountMinor()).isEqualTo(exact.longValueExact());
        } else {
            assertThatThrownBy(() -> money.percentage(bps)).isInstanceOf(ArithmeticException.class);
        }
    }

    @Property(tries = 3_000)
    @Label("percentage con pb de 0 a 10000 nunca lanza y nunca supera el importe en valor absoluto")
    void percentageWithinHundredNeverThrows(@ForAll long a, @ForAll @IntRange(min = 0, max = 10_000) int bps) {
        long result = Money.of(a, Currency.USD).percentage(bps).amountMinor();
        assertThat(Long.signum(result) * Long.signum(a)).isGreaterThanOrEqualTo(0);
        assertThat(BigInteger.valueOf(result).abs()).isLessThanOrEqualTo(BigInteger.valueOf(a).abs());
    }

    @Property(tries = 3_000)
    @Label("percentage es simetrico respecto al signo en todo el rango (salvo Long.MIN_VALUE, sin opuesto)")
    void percentageIsOddEverywhere(@ForAll long a, @ForAll @IntRange(min = 0, max = 10_000) int bps) {
        if (a == Long.MIN_VALUE) {
            return;
        }
        assertThat(Money.of(-a, Currency.EUR).percentage(bps).amountMinor())
                .isEqualTo(-Money.of(a, Currency.EUR).percentage(bps).amountMinor());
    }

    @Property(tries = 2_000)
    @Label("percentage es monotono: mas puntos basicos nunca dan una comision menor sobre un importe positivo")
    void percentageIsMonotonicInBps(@ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long a,
                                    @ForAll @IntRange(min = 0, max = 9_999) int bps) {
        Money money = Money.of(a, Currency.EUR);
        assertThat(money.percentage(bps + 1).amountMinor()).isGreaterThanOrEqualTo(money.percentage(bps).amountMinor());
    }

    @Example
    @Label("bordes de percentage: Long.MIN/MAX_VALUE, mitades negativas y restos de 9999")
    void percentageEdges() {
        Money max = Money.of(Long.MAX_VALUE, Currency.EUR);
        Money min = Money.of(Long.MIN_VALUE, Currency.EUR);
        assertThat(max.percentage(10_000)).isEqualTo(max);
        assertThat(min.percentage(10_000)).isEqualTo(min);
        // 9223372036854775807 x 0,5 = 4611686018427387903,5 -> 4611686018427387904
        assertThat(max.percentage(5_000).amountMinor()).isEqualTo(4_611_686_018_427_387_904L);
        // -9223372036854775808 x 0,5 = -4611686018427387904 exacto
        assertThat(min.percentage(5_000).amountMinor()).isEqualTo(-4_611_686_018_427_387_904L);
        // -9223372036854775807 x 0,5 = -4611686018427387903,5 -> -4611686018427387904 (se aleja del cero)
        assertThat(Money.of(-Long.MAX_VALUE, Currency.EUR).percentage(5_000).amountMinor())
                .isEqualTo(-4_611_686_018_427_387_904L);
        assertThat(min.percentage(1).amountMinor()).isEqualTo(-922_337_203_685_478L); // ...477,5808 -> ...478
        assertThat(max.percentage(1).amountMinor()).isEqualTo(922_337_203_685_478L);  // ...477,5807 -> ...478
        // Mas del 100 % que no cabe: ahi si hay desbordamiento real
        assertThatThrownBy(() -> max.percentage(10_001)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> min.percentage(10_001)).isInstanceOf(ArithmeticException.class);
        assertThat(Money.of(1, Currency.EUR).percentage(Integer.MAX_VALUE).amountMinor())
                .isEqualTo(214_748L); // 214748,3647 -> 214748
        // Restos de 9999 en positivo y negativo
        assertThat(Money.of(9_999, Currency.EUR).percentage(5_000).amountMinor()).isEqualTo(5_000L); // 4999,5
        assertThat(Money.of(-9_999, Currency.EUR).percentage(5_000).amountMinor()).isEqualTo(-5_000L);
        assertThat(Money.of(-19_999, Currency.EUR).percentage(5_000).amountMinor()).isEqualTo(-10_000L);
        assertThat(Money.of(-10_001, Currency.EUR).percentage(5_000).amountMinor()).isEqualTo(-5_001L); // -5000,5
        assertThat(Money.of(-10_001, Currency.EUR).percentage(4_999).amountMinor()).isEqualTo(-4_999L); // -4999,4999
    }

    // ---------------------------------------------------------------------------------
    // parse con signo
    // ---------------------------------------------------------------------------------

    @Property(tries = 3_000)
    @Label("parse('-' + x) == -parse(x) para todo x positivo, y Long.MIN_VALUE se puede escribir")
    void parseNegativeIsNegationOfPositive(@ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long a,
                                           @ForAll("currencies") Currency currency) {
        String positive = Money.of(a, currency).toDecimalString();
        assertThat(Money.parse("-" + positive, currency).amountMinor()).isEqualTo(-a);
        assertThat(Money.parse(Money.of(-a - 1, currency).toDecimalString(), currency).amountMinor())
                .as("tambien el inmediatamente inferior, que llega a Long.MIN_VALUE").isEqualTo(-a - 1);
    }

    @Property(tries = 2_000)
    @Label("parse acepta ceros a la izquierda y decimales cortos igual con signo que sin el")
    void parsePaddingWithSign(@ForAll @LongRange(min = 0, max = 99_999_999L) long major,
                              @ForAll @IntRange(min = 0, max = 9) int tenth,
                              @ForAll @IntRange(min = 0, max = 5) int zeros) {
        String text = "0".repeat(zeros) + major + "." + tenth;
        long expected = major * 100 + tenth * 10L;
        assertThat(Money.parse(text, Currency.EUR).amountMinor()).isEqualTo(expected);
        assertThat(Money.parse("-" + text, Currency.EUR).amountMinor()).isEqualTo(-expected);
    }

    private static boolean fitsInLong(BigInteger value) {
        return value.compareTo(LONG_MIN) >= 0 && value.compareTo(LONG_MAX) <= 0;
    }
}
