package dev.pedrogomez.payaf.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Casos feos de {@link Money}: entradas mal formadas, bordes de {@code long} y nulos. */
class MoneyEdgeCasesTest {

    @ParameterizedTest(name = "parse(«{0}», EUR) se rechaza")
    @ValueSource(strings = {"", " ", "-", ".", "1.", ".5", "+1", " 1", "1 ", "1e2", "1,00", "1.0.0",
            "--1", "0x10", "١٢", "１２", "1_000", "NaN", "Infinity"})
    @DisplayName("parse es estricto con el formato: solo -?dígitos(.dígitos)? en ASCII")
    void parseRejectsMalformedInput(String text) {
        assertThatThrownBy(() -> Money.parse(text, Currency.EUR)).isInstanceOf(InvalidValueException.class);
    }

    @ParameterizedTest(name = "parse(«{0}», JPY) se rechaza")
    @ValueSource(strings = {"1.0", "1.", "0.5", "-0.1", "100.00"})
    @DisplayName("el yen no admite ninguna parte decimal, ni siquiera ceros")
    void jpyRejectsAnyFraction(String text) {
        assertThatThrownBy(() -> Money.parse(text, Currency.JPY)).isInstanceOf(InvalidValueException.class);
    }

    @ParameterizedTest(name = "parse(«{0}», {1}) = {2}")
    @CsvSource({
            "0, EUR, 0",
            "-0, EUR, 0",
            "0.00, EUR, 0",
            "0.01, EUR, 1",
            "-0.01, EUR, -1",
            "0.1, GBP, 10",
            "007.50, USD, 750",
            "1, JPY, 1",
            "-1500, JPY, -1500",
            "92233720368547758.07, EUR, 9223372036854775807",
            "9223372036854775807, JPY, 9223372036854775807"
    })
    @DisplayName("parse acepta los bordes válidos y los pasa a unidades menores")
    void parseValidEdges(String text, Currency currency, long expectedMinor) {
        assertThat(Money.parse(text, currency)).isEqualTo(Money.of(expectedMinor, currency));
    }

    @ParameterizedTest(name = "parse(«{0}», {1}) desborda")
    @CsvSource({
            "92233720368547758.08, EUR",
            "9223372036854775808, JPY",
            "99999999999999999999999999999, EUR",
            "-99999999999999999999999999999, JPY"
    })
    @DisplayName("un importe que no cabe en long lanza ArithmeticException, no NumberFormatException")
    void parseOverflowIsArithmetic(String text, Currency currency) {
        assertThatThrownBy(() -> Money.parse(text, currency)).isExactlyInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("parse acepta Long.MIN_VALUE, que es un importe representable")
    void parseAcceptsLongMinValue() {
        assertThat(Money.parse("-9223372036854775808", Currency.JPY))
                .isEqualTo(Money.of(Long.MIN_VALUE, Currency.JPY));
        assertThat(Money.parse("-92233720368547758.08", Currency.EUR))
                .isEqualTo(Money.of(Long.MIN_VALUE, Currency.EUR));
    }

    @ParameterizedTest(name = "parse({0}, {1}) = {2}")
    @CsvSource({
            "-0.5, EUR, -50",
            "-0.05, EUR, -5",
            "-00.01, GBP, -1",
            "-0.00, EUR, 0",
            "-1.99, USD, -199",
            "-0, JPY, 0",
            "-92233720368547758.07, EUR, -9223372036854775807",
            "-9223372036854775807, JPY, -9223372036854775807",
            "-0092233720368547758.08, EUR, -9223372036854775808"
    })
    @DisplayName("parse con signo: la parte decimal también es negativa")
    void parseWithSign(String text, Currency currency, long expectedMinor) {
        assertThat(Money.parse(text, currency)).isEqualTo(Money.of(expectedMinor, currency));
    }

    @ParameterizedTest(name = "parse({0}, {1}) desborda")
    @CsvSource({
            "-92233720368547758.09, EUR",
            "-92233720368547759, EUR",
            "-9223372036854775809, JPY",
            "92233720368547758.08, GBP"
    })
    @DisplayName("parse con signo: un céntimo más allá de Long.MIN_VALUE desborda con ArithmeticException")
    void parseNegativeOverflow(String text, Currency currency) {
        assertThatThrownBy(() -> Money.parse(text, currency)).isExactlyInstanceOf(ArithmeticException.class);
    }

    @ParameterizedTest(name = "parse({0}) se rechaza")
    @ValueSource(strings = {"-", "--1", "-.5", "- 1", "-+1", "+-1", "1-", "-1-", "−1", "-1.", "-0x1"})
    @DisplayName("parse con el signo mal puesto se rechaza como importe no válido")
    void parseRejectsMisplacedSign(String text) {
        assertThatThrownBy(() -> Money.parse(text, Currency.EUR)).isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("nulos: divisa nula y texto nulo se rechazan al construir")
    void nulls() {
        assertThatThrownBy(() -> Money.of(1, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Money.zero(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Money.parse(null, Currency.EUR)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Money.parse("1", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Money.of(1, Currency.EUR).plus(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Money.of(1, Currency.EUR).minus(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("la resta que se sale de long también lanza ArithmeticException")
    void minusOverflow() {
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, Currency.EUR).minus(Money.of(-1, Currency.EUR)))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.of(Long.MIN_VALUE, Currency.EUR).plus(Money.of(-1, Currency.EUR)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("toDecimalString con importes pequeños, negativos y los bordes de long")
    void toDecimalStringEdges() {
        assertThat(Money.zero(Currency.EUR).toDecimalString()).isEqualTo("0.00");
        assertThat(Money.of(-5, Currency.EUR).toDecimalString()).isEqualTo("-0.05");
        assertThat(Money.of(-50, Currency.GBP).toDecimalString()).isEqualTo("-0.50");
        assertThat(Money.of(100, Currency.USD).toDecimalString()).isEqualTo("1.00");
        assertThat(Money.of(0, Currency.JPY).toDecimalString()).isEqualTo("0");
        assertThat(Money.of(Long.MAX_VALUE, Currency.EUR).toDecimalString()).isEqualTo("92233720368547758.07");
        assertThat(Money.of(Long.MIN_VALUE, Currency.EUR).toDecimalString()).isEqualTo("-92233720368547758.08");
        assertThat(Money.of(-5, Currency.EUR)).hasToString("-0.05 EUR");
    }

    @Test
    @DisplayName("igualdad por valor: mismo importe en otra divisa no es igual, y el cero tampoco")
    void valueEquality() {
        assertThat(Money.of(100, Currency.EUR)).isEqualTo(Money.parse("1", Currency.EUR));
        assertThat(Money.of(100, Currency.EUR)).isNotEqualTo(Money.of(100, Currency.USD));
        assertThat(Money.zero(Currency.EUR)).isNotEqualTo(Money.zero(Currency.JPY));
        assertThat(Money.zero(Currency.EUR).isPositive()).isFalse();
        assertThat(Money.of(-1, Currency.EUR).isPositive()).isFalse();
        assertThat(Money.of(-1, Currency.EUR).isZero()).isFalse();
    }
}
