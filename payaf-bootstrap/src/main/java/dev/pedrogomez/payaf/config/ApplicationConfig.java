package dev.pedrogomez.payaf.config;

import dev.pedrogomez.payaf.application.port.out.CircuitBreakerStore;
import dev.pedrogomez.payaf.application.port.out.EventPublisher;
import dev.pedrogomez.payaf.application.port.out.GatewayCatalog;
import dev.pedrogomez.payaf.application.port.out.IdempotencyStore;
import dev.pedrogomez.payaf.application.port.out.PaymentIdGenerator;
import dev.pedrogomez.payaf.application.port.out.PaymentRepository;
import dev.pedrogomez.payaf.application.port.out.ProcessedNotificationStore;
import dev.pedrogomez.payaf.application.port.out.TransactionRunner;
import dev.pedrogomez.payaf.application.service.AuthorizePaymentService;
import dev.pedrogomez.payaf.application.service.HandleGatewayNotificationService;
import dev.pedrogomez.payaf.application.service.PaymentOperationsService;
import dev.pedrogomez.payaf.application.service.QueryGatewayHealthService;
import dev.pedrogomez.payaf.application.service.QueryPaymentsService;
import dev.pedrogomez.payaf.application.service.ReapStalePaymentsService;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Los casos de uso de la aplicación como beans de Spring.
 * <p>
 * <strong>Es el único sitio donde la aplicación toca Spring.</strong> Los servicios no llevan
 * {@code @Service} ni {@code @Autowired}: son clases Java normales que reciben sus puertos por
 * constructor, y aquí se construyen a mano con los adaptadores que haya en el contexto (los de
 * memoria con el perfil {@code memory}, ver {@link MemoryAdaptersConfig}; el simulador de PSP, ver
 * {@link GatewaySimConfig}). Así la aplicación se puede usar y probar sin Spring, y cambiar de
 * adaptador es cambiar qué configuración se carga, no tocar los servicios.
 * <p>
 * Lo que no es un puerto también se decide aquí: el reloj ({@link Clock#systemUTC()}), los
 * identificadores (UUID aleatorios) y la configuración del breaker
 * ({@code payaf.breaker.*} en {@code application.yml}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BreakerProperties.class)
public class ApplicationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PaymentIdGenerator paymentIdGenerator() {
        return () -> PaymentId.of(UUID.randomUUID());
    }

    @Bean
    CircuitBreakerConfig circuitBreakerConfig(BreakerProperties properties) {
        return properties.toDomain();
    }

    @Bean
    AuthorizePaymentService authorizePaymentService(PaymentRepository payments, IdempotencyStore idempotency,
                                                    GatewayCatalog catalog, CircuitBreakerStore breakers,
                                                    EventPublisher events, TransactionRunner transactions,
                                                    PaymentIdGenerator ids, Clock clock) {
        return new AuthorizePaymentService(payments, idempotency, catalog, breakers, events, transactions, ids, clock);
    }

    /** Implementa a la vez captura, reembolso y anulación. */
    @Bean
    PaymentOperationsService paymentOperationsService(PaymentRepository payments, GatewayCatalog catalog,
                                                      CircuitBreakerStore breakers, EventPublisher events,
                                                      TransactionRunner transactions, Clock clock) {
        return new PaymentOperationsService(payments, catalog, breakers, events, transactions, clock);
    }

    @Bean
    HandleGatewayNotificationService handleGatewayNotificationService(PaymentRepository payments,
                                                                      ProcessedNotificationStore processed,
                                                                      EventPublisher events,
                                                                      TransactionRunner transactions, Clock clock) {
        return new HandleGatewayNotificationService(payments, processed, events, transactions, clock);
    }

    @Bean
    QueryGatewayHealthService queryGatewayHealthService(GatewayCatalog catalog, CircuitBreakerStore breakers) {
        return new QueryGatewayHealthService(catalog, breakers);
    }

    @Bean
    QueryPaymentsService queryPaymentsService(PaymentRepository payments, TransactionRunner transactions) {
        return new QueryPaymentsService(payments, transactions);
    }

    @Bean
    ReapStalePaymentsService reapStalePaymentsService(PaymentRepository payments, IdempotencyStore idempotency,
                                                      EventPublisher events, TransactionRunner transactions,
                                                      Clock clock) {
        return new ReapStalePaymentsService(payments, idempotency, events, transactions, clock);
    }
}
