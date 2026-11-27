package dev.pedrogomez.payaf.domain.routing;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resultado de la política de enrutado: las pasarelas aptas, ya ordenadas de la preferida
 * a la última opción, y las descartadas con su motivo.
 * <p>
 * Es dominio porque es la explicación de una decisión de negocio. Guardar también los
 * descartes permite que el panel responda a «¿por qué esta pasarela y no otra?» sin
 * repetir la lógica fuera del dominio.
 * <p>
 * Es totalmente determinista: con las mismas entradas, da la misma decisión aunque los
 * perfiles lleguen en otro orden. Así dos decisiones se pueden comparar con {@code equals},
 * y el panel y los logs no cambian de una ejecución a otra.
 *
 * @param candidates candidatas en orden de preferencia: la primera es la que se prueba primero
 * @param rejections pasarelas descartadas, ordenadas por {@code GatewayId}
 */
public record RoutingDecision(List<RoutingCandidate> candidates, List<RoutingRejection> rejections) {

    public RoutingDecision {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "las candidatas no pueden ser nulas"));
        rejections = List.copyOf(Objects.requireNonNull(rejections, "los descartes no pueden ser nulos"));
    }

    public boolean hasCandidates() {
        return !candidates.isEmpty();
    }

    /** La pasarela preferida, o vacío si no hay ninguna apta. */
    public Optional<RoutingCandidate> primary() {
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.getFirst());
    }
}
