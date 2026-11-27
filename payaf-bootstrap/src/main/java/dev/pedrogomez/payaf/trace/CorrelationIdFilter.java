package dev.pedrogomez.payaf.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Da a cada petición HTTP su {@code correlationId}: el de la cabecera {@value #HEADER} si el
 * cliente manda uno válido (el panel lo hace, para reconocer su propia traza), o uno nuevo. Se
 * devuelve siempre en la respuesta, con la misma cabecera.
 * <p>
 * Además decide por qué adaptador driving entra la petición, por su ruta: {@code /api/**} es la
 * REST y {@code /webhooks/**} los webhooks de las PSP. Abre el {@link TraceContext} del hilo y lo
 * cierra al acabar, pase lo que pase. Es infraestructura del borde: ni la REST ni los webhooks
 * saben que existe.
 */
public final class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    /** Lo que se acepta del cliente: corto y sin caracteres raros, para que no ensucie registros. */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String correlationId = incoming != null && VALID.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString();
        response.setHeader(HEADER, correlationId);
        try (TraceContext.Scope ignored = TraceContext.open(correlationId, channel(path(request)))) {
            chain.doFilter(request, response);
        }
    }

    /** La ruta sin el {@code context-path} (la app puede vivir bajo {@code /proyecto/PayAF}). */
    static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }

    static String channel(String path) {
        if (path.startsWith("/webhooks/")) {
            return "webhook";
        }
        if (path.startsWith("/api/")) {
            return "rest";
        }
        return null;
    }
}
