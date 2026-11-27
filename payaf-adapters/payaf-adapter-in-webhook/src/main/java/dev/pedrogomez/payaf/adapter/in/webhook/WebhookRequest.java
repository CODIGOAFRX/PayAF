package dev.pedrogomez.payaf.adapter.in.webhook;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Un webhook tal como ha llegado: cabeceras y cuerpo en bruto. La firma se calcula sobre los bytes
 * exactos del cuerpo, así que no se parsea nada antes de verificarla.
 */
public record WebhookRequest(Map<String, String> headers, byte[] body) {

    public WebhookRequest {
        headers = Objects.requireNonNull(headers).entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(e -> e.getKey().toLowerCase(Locale.ROOT), Map.Entry::getValue,
                        (a, b) -> a));
        body = Objects.requireNonNull(body).clone();
    }

    /** Cabecera sin distinguir mayúsculas. */
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
    }

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
