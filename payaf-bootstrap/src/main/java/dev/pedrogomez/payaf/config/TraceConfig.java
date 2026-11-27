package dev.pedrogomez.payaf.config;

import dev.pedrogomez.payaf.trace.CorrelationIdFilter;
import dev.pedrogomez.payaf.trace.PortTraceFeed;
import dev.pedrogomez.payaf.trace.PortTracer;
import java.time.Clock;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * La traza de puertos ({@code port.touched}) que ilumina el hexágono del panel.
 * <p>
 * Todo es borde: el filtro que da a cada petición HTTP su {@code X-Request-Id}, el {@link PortTracer}
 * con el que {@link ApplicationConfig} envuelve los puertos de los casos de uso y el canal por el que
 * salen los eventos hacia el SSE. El dominio y la aplicación no saben nada de esto: es
 * observabilidad cableada desde fuera, y quitar esta clase y los envoltorios deja el núcleo igual.
 */
@Configuration(proxyBeanMethods = false)
public class TraceConfig {

    @Bean
    PortTraceFeed portTraceFeed(Clock clock) {
        return new PortTraceFeed(clock);
    }

    @Bean
    PortTracer portTracer(PortTraceFeed feed) {
        return new PortTracer(feed);
    }

    /** El primero de todos: así hasta un 429 del limitador lleva su {@code X-Request-Id}. */
    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
