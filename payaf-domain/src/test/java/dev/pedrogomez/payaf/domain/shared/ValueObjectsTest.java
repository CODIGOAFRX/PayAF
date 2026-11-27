package dev.pedrogomez.payaf.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ValueObjectsTest {

    @Test
    @DisplayName("Currency conoce los decimales de cada divisa")
    void currencyMinorDigits() {
        assertThat(Currency.EUR.minorDigits()).isEqualTo(2);
        assertThat(Currency.JPY.minorDigits()).isZero();
        assertThat(Currency.GBP.minorUnitsPerMajor()).isEqualTo(100);
    }

    @Test
    @DisplayName("CountryCode exige dos letras mayúsculas")
    void countryCode() {
        assertThat(CountryCode.of("ES").value()).isEqualTo("ES");
        assertThatThrownBy(() -> CountryCode.of("es")).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> CountryCode.of("ESP")).isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("GatewayId exige [a-z0-9-]{2,32}")
    void gatewayId() {
        assertThat(GatewayId.of("stripe-eu").value()).isEqualTo("stripe-eu");
        assertThatThrownBy(() -> GatewayId.of("Stripe")).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> GatewayId.of("a")).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> GatewayId.of("a".repeat(33))).isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("IdempotencyKey admite ASCII imprimible sin espacios en los extremos")
    void idempotencyKey() {
        assertThat(IdempotencyKey.of("order 42/retry").value()).isEqualTo("order 42/retry");
        assertThat(IdempotencyKey.of("k".repeat(255))).isNotNull();
        assertThatThrownBy(() -> IdempotencyKey.of("")).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> IdempotencyKey.of(" k")).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> IdempotencyKey.of("k ")).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> IdempotencyKey.of("cañón")).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> IdempotencyKey.of("k".repeat(256))).isInstanceOf(InvalidValueException.class);
    }
}
