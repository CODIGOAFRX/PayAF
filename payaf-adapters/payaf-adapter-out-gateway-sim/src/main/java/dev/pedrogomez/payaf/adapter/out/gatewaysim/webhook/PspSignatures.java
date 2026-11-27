package dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * HMAC-SHA256 con el que las PSP simuladas firman sus webhooks.
 * <p>
 * <strong>Está duplicado a propósito</strong> en el adaptador de webhooks
 * ({@code adapter.in.webhook}), que tiene su propio verificador. No es un descuido: son dos
 * sistemas distintos que solo comparten un contrato, el formato de firma que publica cada PSP. En
 * la realidad la firma la hace el servidor de Stripe y la comprueba nuestro código, y nunca
 * comparten una línea. Si los dos usaran la misma clase, un error en ella firmaría y verificaría
 * igual de mal y los tests pasarían igualmente; con dos implementaciones, cada una pone a prueba a
 * la otra. Además, un adaptador nunca importa otro.
 * <p>
 * Por eso aquí el HMAC se construye a mano sobre {@link MessageDigest}, siguiendo la RFC 2104
 * ({@code H((K ⊕ opad) ‖ H((K ⊕ ipad) ‖ m))}), mientras que el verificador usa
 * {@code javax.crypto.Mac}: si alguna de las dos se equivoca, las firmas dejan de coincidir. (El
 * simulador, además, es Java puro sin {@code javax}.)
 */
public final class PspSignatures {

    private static final int BLOCK_SIZE = 64;

    private PspSignatures() {
    }

    public static byte[] hmacSha256(String secret, String data) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length > BLOCK_SIZE) {
            key = sha256().digest(key);
        }
        byte[] inner = new byte[BLOCK_SIZE];
        byte[] outer = new byte[BLOCK_SIZE];
        for (int i = 0; i < BLOCK_SIZE; i++) {
            byte k = i < key.length ? key[i] : 0;
            inner[i] = (byte) (k ^ 0x36);
            outer[i] = (byte) (k ^ 0x5c);
        }
        MessageDigest innerDigest = sha256();
        innerDigest.update(inner);
        byte[] innerHash = innerDigest.digest(data.getBytes(StandardCharsets.UTF_8));
        MessageDigest outerDigest = sha256();
        outerDigest.update(outer);
        return outerDigest.digest(innerHash);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no está disponible en esta JVM", e);
        }
    }

    /** Escapa un texto como cadena JSON (con las comillas). Los valores del simulador son ASCII sencillo. */
    public static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
