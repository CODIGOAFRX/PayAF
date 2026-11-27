package dev.pedrogomez.payaf.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    @DisplayName("parse convierte a unidades menores según los decimales de la divisa")
    void parsesToMinorUnits() {
        assertThat(Money.parse("12.34", Currency.EUR)).isEqualTo(Money.of(1234, Currency.EUR));
        assertThat(Money.parse("12.3", Currency.EUR)).isEqualTo(Money.of(1230, Currency.EUR));
        assertThat(Money.parse("12", Currency.USD)).isEqualTo(Money.of(1200, Currency.USD));
        assertThat(Money.parse("1500", Currency.JPY)).isEqualTo(Money.of(1500, Currency.JPY));
    }

    @Test
    @DisplayName("parse rechaza más decimales de los que admite la divisa")
    void parseRejectsTooManyDecimals() {
        assertThatThrownBy(() -> Money.parse("12.345", Currency.EUR)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> Money.parse("12.5", Currency.JPY)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> Money.parse("12,34", Currency.EUR)).isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("toDecimalString es la inversa de parse")
    void decimalStringRoundTrips() {
        assertThat(Money.of(5, Currency.EUR).toDecimalString()).isEqualTo("0.05");
        assertThat(Money.of(-1234, Currency.EUR).toDecimalString()).isEqualTo("-12.34");
        assertThat(Money.of(1500, Currency.JPY).toDecimalString()).isEqualTo("1500");
    }

    @Test
    @DisplayName("suma y resta en la misma divisa")
    void plusAndMinus() {
        Money a = Money.of(1000, Currency.EUR);
        Money b = Money.of(250, Currency.EUR);
        assertThat(a.plus(b)).isEqualTo(Money.of(1250, Currency.EUR));
        assertThat(a.minus(b)).isEqualTo(Money.of(750, Currency.EUR));
        assertThat(a.isGreaterThan(b)).isTrue();
        assertThat(a.minus(a).isZero()).isTrue();
        assertThat(b.minus(a).isPositive()).isFalse();
    }

    @Test
    @DisplayName("mezclar divisas lanza CurrencyMismatchException")
    void mixingCurrenciesFails() {
        assertThatThrownBy(() -> Money.of(1, Currency.EUR).plus(Money.of(1, Currency.USD)))
                .isInstanceOf(CurrencyMismatchException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("CURRENCY_MISMATCH");
    }

    @Test
    @DisplayName("el desbordamiento lanza excepción en lugar de dar la vuelta")
    void overflowThrows() {
        Money max = Money.of(Long.MAX_VALUE, Currency.EUR);
        assertThatThrownBy(() -> max.plus(Money.of(1, Currency.EUR))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.of(Long.MIN_VALUE, Currency.EUR).minus(Money.of(1, Currency.EUR)))
                .isInstanceOf(ArithmeticException.class);
        // percentage no desborda si el resultado cabe: el 100 % de cualquier importe es él mismo.
        assertThat(max.percentage(10_000)).isEqualTo(max);
        assertThat(Money.of(922337203685478L, Currency.EUR).percentage(10_000))
                .isEqualTo(Money.of(922337203685478L, Currency.EUR));
    }

    @Test
    @DisplayName("percentage redondea HALF_UP en unidades menores")
    void percentageRoundsHalfUp() {
        // 2,9 % de 10,05 € = 29,145 céntimos -> 29
        assertThat(Money.of(1005, Currency.EUR).percentage(290)).isEqualTo(Money.of(29, Currency.EUR));
        // 1,5 % de 1,00 € = 1,5 céntimos -> 2 (la mitad sube)
        assertThat(Money.of(100, Currency.EUR).percentage(150)).isEqualTo(Money.of(2, Currency.EUR));
        // 1,4999 % de 1,00 € = 1,4999 céntimos -> 1
        assertThat(Money.of(100, Currency.EUR).percentage(149)).isEqualTo(Money.of(1, Currency.EUR));
        // con negativos, la mitad se aleja del cero
        assertThat(Money.of(-100, Currency.EUR).percentage(150)).isEqualTo(Money.of(-2, Currency.EUR));
        assertThat(Money.of(1234, Currency.EUR).percentage(10_000)).isEqualTo(Money.of(1234, Currency.EUR));
        assertThatThrownBy(() -> Money.of(100, Currency.EUR).percentage(-1))
                .isInstanceOf(InvalidValueException.class);
    }
}
