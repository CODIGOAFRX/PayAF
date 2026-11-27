package dev.pedrogomez.payaf.config;

import dev.pedrogomez.payaf.adapter.out.persistencememory.InMemoryCircuitBreakerStore;
import dev.pedrogomez.payaf.adapter.out.persistencememory.InMemoryEventPublisher;
import dev.pedrogomez.payaf.adapter.out.persistencememory.InMemoryIdempotencyStore;
import dev.pedrogomez.payaf.adapter.out.persistencememory.InMemoryPaymentRepository;
import dev.pedrogomez.payaf.adapter.out.persistencememory.InMemoryProcessedNotificationStore;
import dev.pedrogomez.payaf.adapter.out.persistencememory.InMemoryTransactionRunner;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Perfil {@code memory} (el de por defecto): todos los puertos de persistencia con el adaptador
 * en memoria. Ningún dato sobrevive a un reinicio y las transacciones no se deshacen (ver
 * {@link InMemoryTransactionRunner}). El perfil {@code postgres} tendrá su propia configuración
 * con los adaptadores JPA y el outbox.
 */
@Configuration(proxyBeanMethods = false)
@Profile("memory")
public class MemoryAdaptersConfig {

    @Bean
    InMemoryPaymentRepository paymentRepository() {
        return new InMemoryPaymentRepository();
    }

    @Bean
    InMemoryIdempotencyStore idempotencyStore() {
        return new InMemoryIdempotencyStore();
    }

    @Bean
    InMemoryCircuitBreakerStore circuitBreakerStore(CircuitBreakerConfig config) {
        return new InMemoryCircuitBreakerStore(config);
    }

    @Bean
    InMemoryProcessedNotificationStore processedNotificationStore() {
        return new InMemoryProcessedNotificationStore();
    }

    @Bean
    InMemoryEventPublisher eventPublisher() {
        return new InMemoryEventPublisher();
    }

    @Bean
    InMemoryTransactionRunner transactionRunner() {
        return new InMemoryTransactionRunner();
    }
}
