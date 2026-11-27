package dev.pedrogomez.payaf.domain.routing;

import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lo que el negocio sabe de una pasarela: qué divisas y países admite, cuánto cobra y si
 * está habilitada.
 * <p>
 * Es dominio porque son las condiciones comerciales que pactamos con cada PSP y la materia
 * prima de la política de enrutado. No dice nada de cómo se habla con la pasarela (URL,
 * credenciales, timeouts): eso es del adaptador.
 *
 * @param id             identificador de la pasarela
 * @param currencies     divisas admitidas; al menos una
 * @param countries      países admitidos; vacío significa «todos»
 * @param feeBasisPoints comisión variable en puntos básicos, de 0 a 10.000 (290 = 2,9 %)
 * @param fixedFeeMinor  comisión fija por operación, en unidades menores de cada divisa; si
 *                       una divisa admitida no aparece, su comisión fija es cero
 * @param enabled        si la pasarela está habilitada para recibir tráfico
 */
public record GatewayProfile(
        GatewayId id,
        Set<Currency> currencies,
        Set<CountryCode> countries,
        int feeBasisPoints,
        Map<Currency, Long> fixedFeeMinor,
        boolean enabled) {

    private static final int MAX_BASIS_POINTS = 10_000;

    public GatewayProfile {
        Objects.requireNonNull(id, "el identificador no puede ser nulo");
        currencies = Set.copyOf(Objects.requireNonNull(currencies, "las divisas no pueden ser nulas"));
        countries = Set.copyOf(Objects.requireNonNull(countries, "los países no pueden ser nulos"));
        fixedFeeMinor = Map.copyOf(Objects.requireNonNull(fixedFeeMinor, "las comisiones fijas no pueden ser nulas"));
        if (currencies.isEmpty()) {
            throw new InvalidValueException("La pasarela " + id + " debe admitir al menos una divisa");
        }
        if (feeBasisPoints < 0 || feeBasisPoints > MAX_BASIS_POINTS) {
            throw new InvalidValueException("La comisión de " + id + " debe estar entre 0 y 10.000 pb: "
                    + feeBasisPoints);
        }
        for (Map.Entry<Currency, Long> fee : fixedFeeMinor.entrySet()) {
            if (fee.getValue() < 0) {
                throw new InvalidValueException("La comisión fija de " + id + " en " + fee.getKey()
                        + " no puede ser negativa");
            }
            if (!currencies.contains(fee.getKey())) {
                throw new InvalidValueException("La pasarela " + id + " tiene comisión fija en " + fee.getKey()
                        + " pero no admite esa divisa");
            }
        }
    }

    public boolean supports(Currency currency) {
        return currencies.contains(currency);
    }

    /** Un conjunto de países vacío significa que la pasarela opera en todos. */
    public boolean supports(CountryCode country) {
        return countries.isEmpty() || countries.contains(country);
    }

    /**
     * Comisión estimada de cobrar este importe: la parte variable (redondeada HALF_UP, ver
     * {@link Money#percentage}) más la fija de la divisa.
     *
     * @throws InvalidValueException si la pasarela no admite la divisa del importe
     */
    public Money estimatedFee(Money amount) {
        Objects.requireNonNull(amount, "el importe no puede ser nulo");
        if (!supports(amount.currency())) {
            throw new InvalidValueException("La pasarela " + id + " no admite " + amount.currency());
        }
        Money fixed = Money.of(fixedFeeMinor.getOrDefault(amount.currency(), 0L), amount.currency());
        return amount.percentage(feeBasisPoints).plus(fixed);
    }
}
