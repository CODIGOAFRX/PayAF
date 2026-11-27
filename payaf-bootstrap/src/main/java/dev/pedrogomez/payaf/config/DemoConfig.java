package dev.pedrogomez.payaf.config;

import dev.pedrogomez.payaf.application.port.out.CircuitBreakerStore;
import dev.pedrogomez.payaf.application.port.out.EventPublisher;
import dev.pedrogomez.payaf.application.port.out.TransactionRunner;
import dev.pedrogomez.payaf.demo.BreakerReset;
import dev.pedrogomez.payaf.demo.PaymentRateLimitFilter;
import dev.pedrogomez.payaf.demo.SlidingWindowRateLimiter;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Lo que necesita la demo pública y no es producto: restaurar los breakers ({@link BreakerReset}) y
 * el límite de pagos por IP ({@code payaf.demo.payments-per-minute}, 30 por defecto). El limitador
 * usa el reloj del sistema y no el bean {@code clock}, que los tests pueden congelar.
 */
@Configuration(proxyBeanMethods = false)
public class DemoConfig {

    @Bean
    BreakerReset breakerReset(CircuitBreakerStore breakers, EventPublisher events, TransactionRunner transactions,
                              Clock clock) {
        return new BreakerReset(breakers, events, transactions, clock);
    }

    @Bean
    SlidingWindowRateLimiter paymentRateLimiter(@Value("${payaf.demo.payments-per-minute:30}") int perMinute) {
        return new SlidingWindowRateLimiter(perMinute, Duration.ofMinutes(1), Clock.systemUTC());
    }

    @Bean
    FilterRegistrationBean<PaymentRateLimitFilter> paymentRateLimitFilter(SlidingWindowRateLimiter limiter) {
        FilterRegistrationBean<PaymentRateLimitFilter> registration =
                new FilterRegistrationBean<>(new PaymentRateLimitFilter(limiter));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/api/payments", "/api/payments/");
        return registration;
    }
}
