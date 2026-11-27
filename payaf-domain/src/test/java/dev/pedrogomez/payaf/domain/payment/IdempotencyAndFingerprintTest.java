package dev.pedrogomez.payaf.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Huella de la orden y clave de idempotencia: lo que decide si una orden repetida es la
 * misma (se devuelve el pago ya creado) o un conflicto.
 */
class IdempotencyAndFingerprintTest {

    private static final IdempotencyKey KEY = IdempotencyKey.of("k-1");

    private static PaymentRequest req(long minor, Currency currency, String country, String reference) {
        return new PaymentRequest(Money.of(minor, currency), CountryCode.of(country), reference, KEY);
    }

    @Provide
    Arbitrary<PaymentRequest> requests() {
        Arbitrary<Long> minor = Arbitraries.longs().between(1, Long.MAX_VALUE);
        Arbitrary<Currency> currency = Arbitraries.of(Currency.class);
        Arbitrary<String> country = Arbitraries.strings().withCharRange('A', 'Z').ofLength(2);
        Arbitrary<String> reference = Arbitraries.strings().all().ofMinLength(1).ofMaxLength(40)
                .filter(s -> !s.isBlank())
                .injectDuplicates(0.1);
        Arbitrary<String> key = Arbitraries.strings().withCharRange('!', '~').ofMinLength(1).ofMaxLength(20);
        return Combinators.combine(minor, currency, country, reference, key)
                .as((m, c, co, r, k) -> new PaymentRequest(Money.of(m, c), CountryCode.of(co), r,
                        IdempotencyKey.of(k)));
    }

    // ---------------------------------------------------------------------------------
    // Huella
    // ---------------------------------------------------------------------------------

    @Property
    @Label("la misma orden da la misma huella, y la clave de idempotencia no entra en ella")
    void sameContentSameFingerprintRegardlessOfKey(@ForAll("requests") PaymentRequest r,
                                                    @ForAll("requests") PaymentRequest other) {
        PaymentRequest copy = new PaymentRequest(r.amount(), r.country(), r.merchantReference(), r.key());
        PaymentRequest otherKey = new PaymentRequest(r.amount(), r.country(), r.merchantReference(), other.key());
        assertThat(copy.fingerprint()).isEqualTo(r.fingerprint());
        assertThat(otherKey.fingerprint()).isEqualTo(r.fingerprint());
        assertThat(r.fingerprint().value()).matches("[0-9a-f]{64}");
    }

    @Property
    @Label("cambiar cualquier campo del contenido cambia la huella")
    void anyContentChangeChangesFingerprint(@ForAll("requests") PaymentRequest a, @ForAll("requests") PaymentRequest b) {
        boolean sameContent = a.amount().equals(b.amount()) && a.country().equals(b.country())
                && a.merchantReference().equals(b.merchantReference());
        if (sameContent) {
            assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
        } else {
            assertThat(a.fingerprint()).isNotEqualTo(b.fingerprint());
        }
    }

    @Example
    @Label("cada campo por separado: importe, divisa, país y referencia")
    void eachFieldMatters() {
        PaymentRequest base = req(1000, Currency.EUR, "ES", "pedido-1");
        assertThat(req(1001, Currency.EUR, "ES", "pedido-1").fingerprint()).isNotEqualTo(base.fingerprint());
        assertThat(req(1000, Currency.USD, "ES", "pedido-1").fingerprint()).isNotEqualTo(base.fingerprint());
        assertThat(req(1000, Currency.JPY, "ES", "pedido-1").fingerprint()).isNotEqualTo(base.fingerprint());
        assertThat(req(1000, Currency.EUR, "PT", "pedido-1").fingerprint()).isNotEqualTo(base.fingerprint());
        assertThat(req(1000, Currency.EUR, "ES", "pedido-2").fingerprint()).isNotEqualTo(base.fingerprint());
        assertThat(req(1000, Currency.EUR, "ES", "Pedido-1").fingerprint()).isNotEqualTo(base.fingerprint());
        assertThat(req(1000, Currency.EUR, "ES", "pedido-1 ").fingerprint()).isNotEqualTo(base.fingerprint());
    }

    @Example
    @Label("el mismo importe escrito de dos formas («10» y «10.00») es la misma orden")
    void sameAmountDifferentNotationSameFingerprint() {
        PaymentRequest a = new PaymentRequest(Money.parse("10", Currency.EUR), CountryCode.of("ES"), "r", KEY);
        PaymentRequest b = new PaymentRequest(Money.parse("10.00", Currency.EUR), CountryCode.of("ES"), "r", KEY);
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
    }

    @Example
    @Label("barras verticales en la referencia no provocan colisiones con otros campos")
    void pipesInReferenceDoNotCollide() {
        List<PaymentRequest> tricky = List.of(
                req(100, Currency.EUR, "ES", "a|EUR"),
                req(100, Currency.EUR, "ES", "a"),
                req(100, Currency.EUR, "ES", "EUR|a"),
                req(100, Currency.EUR, "ES", "ES|a"),
                req(100, Currency.EUR, "ES", "100|EUR|ES|a"),
                req(100, Currency.EUR, "ES", "a|EUR|ES"),
                req(100, Currency.EUR, "ES", "|"),
                req(100, Currency.EUR, "ES", "||"),
                req(100, Currency.EUR, "ES", "a|"),
                req(100, Currency.EUR, "ES", "|a"),
                req(100, Currency.EUR, "ES", "v1|100|EUR|ES|a"),
                req(100, Currency.USD, "ES", "a|EUR"),
                req(100, Currency.EUR, "US", "a|EUR"),
                req(10, Currency.EUR, "ES", "0|EUR|ES|a"),
                req(1, Currency.EUR, "ES", "00|EUR|ES|a"),
                req(100, Currency.EUR, "ES", "a\u0000EUR"),
                req(100, Currency.EUR, "ES", "a\nEUR"));
        Set<RequestFingerprint> fingerprints = new HashSet<>();
        tricky.forEach(r -> fingerprints.add(r.fingerprint()));
        assertThat(fingerprints).as("todas las huellas deben ser distintas").hasSameSizeAs(tricky);
    }

    @Example
    @Label("la huella es estable entre versiones: v1|importeMenor|divisa|país|referencia en SHA-256")
    void fingerprintIsStableAcrossReleases() {
        // Valor dorado calculado fuera de Java con: printf 'v1|10000|EUR|ES|pedido-1' | sha256sum
        // Si cambia, las órdenes ya guardadas dejarían de reconocerse como repetidas.
        assertThat(req(10_000, Currency.EUR, "ES", "pedido-1").fingerprint().value())
                .isEqualTo("03c545f6cc0e7d31881af158f85c6db1f424ee5563ed02068c4d698ddf067bef");
    }

    @Example
    @Label("la referencia admite Unicode y se codifica en UTF-8 sin perder información")
    void unicodeReference() {
        assertThat(req(100, Currency.EUR, "ES", "café").fingerprint())
                .isNotEqualTo(req(100, Currency.EUR, "ES", "cafe").fingerprint());
        assertThat(req(100, Currency.EUR, "ES", "pedido-😀").fingerprint())
                .isNotEqualTo(req(100, Currency.EUR, "ES", "pedido-?").fingerprint());
    }

    @Example
    @Label("RequestFingerprint solo admite 64 hexadecimales en minúsculas")
    void fingerprintFormat() {
        assertThatThrownBy(() -> new RequestFingerprint("A".repeat(64))).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new RequestFingerprint("a".repeat(63))).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new RequestFingerprint("g".repeat(64))).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new RequestFingerprint(null)).isInstanceOf(NullPointerException.class);
        assertThat(new RequestFingerprint("0".repeat(64)).value()).hasSize(64);
    }

    // ---------------------------------------------------------------------------------
    // PaymentRequest: casos feos
    // ---------------------------------------------------------------------------------

    @Example
    @Label("PaymentRequest rechaza nulos, referencia en blanco o de más de 255 caracteres")
    void paymentRequestValidation() {
        Money amount = Money.of(100, Currency.EUR);
        CountryCode es = CountryCode.of("ES");
        assertThatThrownBy(() -> new PaymentRequest(null, es, "r", KEY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentRequest(amount, null, "r", KEY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentRequest(amount, es, null, KEY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentRequest(amount, es, "r", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentRequest(amount, es, "", KEY)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new PaymentRequest(amount, es, " \t\n", KEY)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new PaymentRequest(amount, es, "r".repeat(256), KEY))
                .isInstanceOf(InvalidValueException.class);
        assertThat(new PaymentRequest(amount, es, "r".repeat(255), KEY).merchantReference()).hasSize(255);
        assertThat(new PaymentRequest(Money.of(Long.MAX_VALUE, Currency.JPY), es, "r", KEY).amount().amountMinor())
                .isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> new PaymentRequest(Money.of(Long.MIN_VALUE, Currency.EUR), es, "r", KEY))
                .isInstanceOf(InvalidValueException.class);
    }

    // ---------------------------------------------------------------------------------
    // IdempotencyKey
    // ---------------------------------------------------------------------------------

    @Property
    @Label("cualquier ASCII imprimible de 1 a 255 caracteres sin espacios en los extremos es una clave válida")
    void validKeysAccepted(@ForAll("validKeys") String value) {
        assertThat(IdempotencyKey.of(value).value()).isEqualTo(value);
    }

    @Provide
    Arbitrary<String> validKeys() {
        return Arbitraries.strings().withCharRange(' ', '~').ofMinLength(1).ofMaxLength(255)
                .filter(s -> s.charAt(0) != ' ' && s.charAt(s.length() - 1) != ' ');
    }

    @Property
    @Label("un solo carácter fuera de 0x20-0x7E invalida la clave")
    void nonPrintableOrNonAsciiRejected(@ForAll("validKeys") String prefix,
                                        @ForAll("forbiddenChars") char bad) {
        String value = (prefix + bad + "x");
        if (value.length() > 255) {
            value = value.substring(value.length() - 255);
        }
        String candidate = value;
        assertThatThrownBy(() -> IdempotencyKey.of(candidate)).isInstanceOf(InvalidValueException.class);
    }

    @Provide
    Arbitrary<Character> forbiddenChars() {
        return Arbitraries.oneOf(
                Arbitraries.chars().range('\u0000', '\u001F'),
                Arbitraries.chars().range('\u007F', '￿'));
    }

    @Example
    @Label("IdempotencyKey: bordes de longitud, espacios en los extremos y caracteres invisibles")
    void idempotencyKeyEdges() {
        assertThat(IdempotencyKey.of("!").value()).isEqualTo("!");
        assertThat(IdempotencyKey.of("~").value()).isEqualTo("~");
        assertThat(IdempotencyKey.of("a" + " ".repeat(253) + "b").value()).hasSize(255);
        assertThatThrownBy(() -> IdempotencyKey.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> IdempotencyKey.of(" ")).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> IdempotencyKey.of("   ")).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> IdempotencyKey.of("k\t")).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> IdempotencyKey.of("\tk")).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> IdempotencyKey.of("k\n")).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> IdempotencyKey.of("k ")).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> IdempotencyKey.of("k​")).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> IdempotencyKey.of("k\u007F")).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> IdempotencyKey.of("clave-😀")).isInstanceOf(InvalidValueException.class);
        // 255 caracteres de un byte frente a 256: el límite es exacto
        assertThatThrownBy(() -> IdempotencyKey.of("a".repeat(255) + "b")).isInstanceOf(InvalidValueException.class);
        // La comparación es exacta: no se normalizan mayúsculas
        assertThat(IdempotencyKey.of("Key")).isNotEqualTo(IdempotencyKey.of("key"));
    }
}
