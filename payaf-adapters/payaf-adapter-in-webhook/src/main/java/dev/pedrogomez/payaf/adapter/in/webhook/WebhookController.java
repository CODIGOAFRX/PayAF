package dev.pedrogomez.payaf.adapter.in.webhook;

import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.application.port.in.GatewayNotification;
import dev.pedrogomez.payaf.application.port.in.HandleGatewayNotificationUseCase;
import dev.pedrogomez.payaf.application.port.in.NotificationResult;
import dev.pedrogomez.payaf.domain.shared.DomainException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador de entrada (driving) para las notificaciones de las PSP: {@code POST /webhooks/{gateway}}.
 * <p>
 * Orden estricto: primero se verifica la firma con el {@link WebhookParser} de esa PSP; si falla,
 * 401 y la aplicación ni se entera. Solo una notificación verificada y traducida llega a
 * {@link HandleGatewayNotificationUseCase}.
 * <p>
 * Respuestas, pensadas para cómo reintentan las PSP (reintentan hasta recibir un 2xx):
 * <ul>
 *   <li>APPLIED, DUPLICATE, ALREADY_IN_STATE y UNKNOWN_PAYMENT → 200. Ninguno se arregla
 *       reintentando, así que se acusa recibo.</li>
 *   <li>Una {@link DomainException} o {@link ApplicationException} (p. ej. un conflicto de
 *       versión) → 409, para que la PSP reintente más tarde; la marca de procesada se ha deshecho
 *       con la transacción.</li>
 *   <li>Firma inválida → 401; contenido ilegible con firma buena → 400; PSP desconocida → 404.</li>
 * </ul>
 */
@RestController
public class WebhookController {

    private final HandleGatewayNotificationUseCase notifications;
    private final Map<String, WebhookParser> parsers;

    public WebhookController(HandleGatewayNotificationUseCase notifications, List<WebhookParser> parsers) {
        this.notifications = Objects.requireNonNull(notifications);
        this.parsers = parsers.stream().collect(Collectors.toUnmodifiableMap(WebhookParser::gateway,
                Function.identity()));
    }

    @PostMapping("/webhooks/{gateway}")
    public ResponseEntity<?> receive(@PathVariable String gateway, @RequestHeader HttpHeaders headers,
                                     @RequestBody(required = false) byte[] body) {
        WebhookParser parser = parsers.get(gateway);
        if (parser == null) {
            return problem(HttpStatus.NOT_FOUND, "UNKNOWN_GATEWAY", "No hay webhooks para la pasarela " + gateway);
        }
        Map<String, String> flat = new LinkedHashMap<>();
        headers.forEach((name, values) -> flat.put(name, String.join(",", values)));
        List<GatewayNotification> parsed;
        try {
            parsed = parser.parse(new WebhookRequest(flat, body == null ? new byte[0] : body));
        } catch (InvalidSignatureException e) {
            return problem(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", e.getMessage());
        } catch (MalformedWebhookException e) {
            return problem(HttpStatus.BAD_REQUEST, "MALFORMED_WEBHOOK", e.getMessage());
        }
        List<String> results = new ArrayList<>();
        try {
            for (GatewayNotification notification : parsed) {
                NotificationResult result = notifications.handle(notification);
                results.add(result.name());
            }
        } catch (DomainException e) {
            return problem(HttpStatus.CONFLICT, e.code(), e.getMessage());
        } catch (ApplicationException e) {
            return problem(HttpStatus.CONFLICT, e.code(), e.getMessage());
        }
        return ResponseEntity.ok(new WebhookResponse(results.isEmpty() ? List.of("IGNORED") : results));
    }

    /** Cuerpo del 200: qué se hizo con cada notificación ({@code IGNORED} si no traía ninguna útil). */
    public record WebhookResponse(List<String> results) {
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }
}
