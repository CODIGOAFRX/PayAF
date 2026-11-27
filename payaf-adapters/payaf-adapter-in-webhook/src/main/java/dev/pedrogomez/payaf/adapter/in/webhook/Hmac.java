package dev.pedrogomez.payaf.adapter.in.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 para verificar firmas, con {@code javax.crypto.Mac}.
 * <p>
 * <strong>Duplicado a propósito</strong> respecto al firmador del simulador
 * ({@code gatewaysim.webhook.PspSignatures}, que lo implementa a mano según la RFC 2104). Firmar y
 * verificar son dos sistemas distintos que solo comparten el contrato que publica cada PSP: en la
 * realidad, quien firma es el servidor de Stripe y nunca compartiría código con nosotros. Si los
 * dos usaran la misma clase, un fallo en ella firmaría y verificaría igual de mal sin que nadie lo
 * notara; con dos implementaciones independientes, cada una pone a prueba a la otra. Y un
 * adaptador no puede importar otro.
 */
final class Hmac {

    private Hmac() {
    }

    static byte[] sha256(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 no está disponible en esta JVM", e);
        }
    }

    static byte[] sha256(String secret, String data) {
        return sha256(secret, data.getBytes(StandardCharsets.UTF_8));
    }

    /** Comparación en tiempo constante: no revela por el tiempo de respuesta cuántos bytes acertó un atacante. */
    static boolean matches(byte[] expected, byte[] received) {
        return received != null && MessageDigest.isEqual(expected, received);
    }
}
