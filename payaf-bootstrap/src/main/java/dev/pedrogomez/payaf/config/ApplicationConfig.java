package dev.pedrogomez.payaf.config;

import dev.pedrogomez.payaf.application.port.out.CircuitBreakerStore;
import dev.pedrogomez.payaf.application.port.out.EventPublisher;
import dev.pedrogomez.payaf.application.port.out.EventStream;
import dev.pedrogomez.payaf.application.port.out.OutboxReader;
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
import dev.pedrogomez.payaf.application.service.RelayOutboxService;
import dev.pedrogomez.payaf.application.port.in.AuthorizePaymentUseCase;
import dev.pedrogomez.payaf.application.port.in.CancelPaymentUseCase;
import dev.pedrogomez.payaf.application.port.in.CapturePaymentUseCase;
import dev.pedrogomez.payaf.application.port.in.HandleGatewayNotificationUseCase;
import dev.pedrogomez.payaf.application.port.in.PaymentView;
import dev.pedrogomez.payaf.application.port.in.RefundPaymentUseCase;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.shared.Money;
import dev.pedrogomez.payaf.trace.PortTracer;
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
 * memoria con el perfil {@code memory}, ver {@link MemoryAdaptersConfig}, o los de PostgreSQL con el
 * perfil {@code postgres}, ver {@link PostgresAdaptersConfig}; el simulador de PSP, ver
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

    /**
     * Autorizar. Los tres casos de uso que mueven pagos reciben sus puertos envueltos por el
     * {@link PortTracer} y salen envueltos ellos mismos como puerto de entrada: es la traza
     * {@code port.touched} del panel. Los servicios no cambian; solo cambia lo que se les inyecta.
     */
    @Bean
    AuthorizePaymentUseCase authorizePaymentService(PaymentRepository payments, IdempotencyStore idempotency,
                                                    GatewayCatalog catalog, CircuitBreakerStore breakers,
                                                    EventPublisher events, TransactionRunner transactions,
                                                    PaymentIdGenerator ids, Clock clock, PortTracer tracer) {
        TracedPorts p = new TracedPorts(tracer, payments, catalog, breakers, events, transactions);
        return tracer.driving(AuthorizePaymentUseCase.class, new AuthorizePaymentService(p.payments,
                traced(tracer, IdempotencyStore.class, idempotency), p.catalog, p.breakers, p.events,
                p.transactions, ids, clock));
    }

    /** Implementa a la vez captura, reembolso y anulación. */
    @Bean
    PaymentOperations paymentOperationsService(PaymentRepository payments, GatewayCatalog catalog,
                                               CircuitBreakerStore breakers, EventPublisher events,
                                               TransactionRunner transactions, Clock clock, PortTracer tracer) {
        TracedPorts p = new TracedPorts(tracer, payments, catalog, breakers, events, transactions);
        PaymentOperationsService service = new PaymentOperationsService(p.payments, p.catalog, p.breakers, p.events,
                p.transactions, clock);
        return tracer.driving(PaymentOperations.class, new PaymentOperations() {
            @Override
            public PaymentView capture(PaymentId id) {
                return service.capture(id);
            }

            @Override
            public PaymentView cancel(PaymentId id) {
                return service.cancel(id);
            }

            @Override
            public PaymentView refund(PaymentId id, Money amount) {
                return service.refund(id, amount);
            }
        });
    }

    @Bean
    HandleGatewayNotificationUseCase handleGatewayNotificationService(PaymentRepository payments,
                                                                      ProcessedNotificationStore processed,
                                                                      EventPublisher events,
                                                                      TransactionRunner transactions, Clock clock,
                                                                      PortTracer tracer) {
        String persistence = PortTracer.persistenceAdapter(payments);
        return tracer.driving(HandleGatewayNotificationUseCase.class, new HandleGatewayNotificationService(
                tracer.driven(PaymentRepository.class, payments, persistence),
                tracer.driven(ProcessedNotificationStore.class, processed, persistence),
                tracer.driven(EventPublisher.class, events, OUTBOX),
                tracer.driven(TransactionRunner.class, transactions, persistence), clock));
    }

    /** Captura, anulación y reembolso juntos, como los implementa {@link PaymentOperationsService}. */
    public interface PaymentOperations extends CapturePaymentUseCase, CancelPaymentUseCase, RefundPaymentUseCase {
    }

    /** En el hexágono del panel, el publicador de eventos es el nodo «Outbox» en los dos perfiles. */
    private static final String OUTBOX = "outbox";

    private static <T> T traced(PortTracer tracer, Class<T> port, T target) {
        return tracer.driven(port, target, PortTracer.persistenceAdapter(target));
    }

    /** Los puertos de salida comunes, envueltos para la traza. */
    private static final class TracedPorts {

        final PaymentRepository payments;
        final GatewayCatalog catalog;
        final CircuitBreakerStore breakers;
        final EventPublisher events;
        final TransactionRunner transactions;

        TracedPorts(PortTracer tracer, PaymentRepository payments, GatewayCatalog catalog,
                    CircuitBreakerStore breakers, EventPublisher events, TransactionRunner transactions) {
            this.payments = traced(tracer, PaymentRepository.class, payments);
            this.catalog = tracer.catalog(catalog);
            this.breakers = traced(tracer, CircuitBreakerStore.class, breakers);
            this.events = tracer.driven(EventPublisher.class, events, OUTBOX);
            this.transactions = traced(tracer, TransactionRunner.class, transactions);
        }
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

    /** El relay del outbox: lee del {@code OutboxReader} del perfil activo y entrega al broadcaster. */
    @Bean
    RelayOutboxService relayOutboxService(OutboxReader outbox, EventStream stream, TransactionRunner transactions) {
        return new RelayOutboxService(outbox, stream, transactions);
    }
}
