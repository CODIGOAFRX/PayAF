package dev.pedrogomez.payaf.domain.payment;

import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Orden de pago tal como la pide el comercio, antes de que exista el pago.
 * <p>
 * Es dominio porque recoge lo que el negocio necesita para decidir: el importe (que
 * debe ser positivo: no se cobra cero ni se cobra en negativo), el país (que condiciona
 * qué pasarelas sirven), la referencia del comercio y la clave de idempotencia.
 *
 * @param amount            importe a cobrar, estrictamente positivo
 * @param country           país del pago
 * @param merchantReference referencia del comercio (su número de pedido), de 1 a 255 caracteres y no en blanco
 * @param key               clave de idempotencia
 */
public record PaymentRequest(Money amount, CountryCode country, String merchantReference, IdempotencyKey key) {

    private static final int MAX_REFERENCE_LENGTH = 255;

    /** Versión del formato canónico: si algún día cambia el formato, cambia el prefijo y las huellas no se confunden. */
    private static final String CANONICAL_VERSION = "v1";

    public PaymentRequest {
        Objects.requireNonNull(amount, "el importe no puede ser nulo");
        Objects.requireNonNull(country, "el país no puede ser nulo");
        Objects.requireNonNull(merchantReference, "la referencia del comercio no puede ser nula");
        Objects.requireNonNull(key, "la clave de idempotencia no puede ser nula");
        if (!amount.isPositive()) {
            throw new InvalidValueException("El importe del pago debe ser mayor que cero: " + amount);
        }
        if (merchantReference.isBlank() || merchantReference.length() > MAX_REFERENCE_LENGTH) {
            throw new InvalidValueException("La referencia del comercio debe tener entre 1 y 255 caracteres");
        }
    }

    /**
     * Huella SHA-256 de un canónico estable: {@code v1|importeMenor|divisa|país|referencia}.
     * <p>
     * No incluye la clave de idempotencia, porque lo que se compara es el contenido de dos
     * órdenes que llegan con la misma clave. La referencia va la última: así, una barra
     * vertical dentro de ella no puede desplazar los demás campos y provocar colisiones.
     */
    public RequestFingerprint fingerprint() {
        String canonical = String.join("|",
                CANONICAL_VERSION,
                Long.toString(amount.amountMinor()),
                amount.currency().name(),
                country.value(),
                merchantReference);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return new RequestFingerprint(HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException e) {
            // Todo JDK está obligado a incluir SHA-256, así que esto no puede ocurrir.
            throw new IllegalStateException("El JDK no ofrece SHA-256", e);
        }
    }
}
