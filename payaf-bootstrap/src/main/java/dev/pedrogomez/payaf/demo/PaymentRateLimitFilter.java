package dev.pedrogomez.payaf.demo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protege la demo pública: como mucho N pagos ({@code POST /api/payments}) por minuto y por IP.
 * El que se pasa recibe un 429 {@code application/problem+json} con {@code code} RATE_LIMITED y la
 * cabecera {@code Retry-After}, y la petición no llega ni al adaptador REST.
 * <p>
 * <strong>Qué IP.</strong> En producción la app va detrás de un Worker de Cloudflare, así que la
 * dirección remota es la del Worker, la misma para todos. Se usa {@code CF-Connecting-IP} (que pone
 * Cloudflare y el visitante no puede falsear al pasar por su red); si no está,
 * el primer salto de {@code X-Forwarded-For}; y si tampoco, la dirección remota. Fuera de
 * Cloudflare esas cabeceras se pueden inventar: es un freno para la demo, no una defensa.
 */
public final class PaymentRateLimitFilter extends OncePerRequestFilter {

    public static final String CODE = "RATE_LIMITED";

    private final SlidingWindowRateLimiter limiter;

    public PaymentRateLimitFilter(SlidingWindowRateLimiter limiter) {
        this.limiter = Objects.requireNonNull(limiter);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath() == null ? "" : request.getContextPath();
        String path = uri.startsWith(context) ? uri.substring(context.length()) : uri;
        return !("POST".equals(request.getMethod()) && ("/api/payments".equals(path) || "/api/payments/".equals(path)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SlidingWindowRateLimiter.Decision decision = limiter.tryAcquire(clientIp(request));
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String detail = "Has lanzado más de " + limiter.limit() + " pagos en un minuto. La demo es compartida: "
                + "espera " + decision.retryAfterSeconds() + " s y vuelve a probar.";
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"detail\":\"" + detail + "\",\"instance\":\"" + jsonSafe(request.getRequestURI()) + "\","
                + "\"code\":\"" + CODE + "\",\"retryAfterSeconds\":" + decision.retryAfterSeconds() + "}");
    }

    static String clientIp(HttpServletRequest request) {
        String cf = request.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) {
            return cf.trim();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String jsonSafe(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
