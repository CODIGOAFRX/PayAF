package dev.pedrogomez.payaf.config;

import dev.pedrogomez.payaf.adapter.out.persistencejpa.JdbcCircuitBreakerStore;
import dev.pedrogomez.payaf.adapter.out.persistencejpa.JdbcIdempotencyStore;
import dev.pedrogomez.payaf.adapter.out.persistencejpa.JdbcOutboxReader;
import dev.pedrogomez.payaf.adapter.out.persistencejpa.JdbcProcessedNotificationStore;
import dev.pedrogomez.payaf.adapter.out.persistencejpa.JpaPaymentRepository;
import dev.pedrogomez.payaf.adapter.out.persistencejpa.OutboxEventPublisher;
import dev.pedrogomez.payaf.adapter.out.persistencejpa.SpringTransactionRunner;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Perfil {@code postgres}: los mismos puertos que {@link MemoryAdaptersConfig}, con los adaptadores
 * de {@code persistence-jpa}. {@link ApplicationConfig} no cambia en absoluto: los casos de uso
 * reciben puertos y no saben cuál de las dos configuraciones se ha cargado.
 * <p>
 * El {@code DataSource}, el {@code EntityManagerFactory}, el {@code JpaTransactionManager} y
 * Flyway los pone la autoconfiguración de Spring Boot (ver {@code application.yml}). Aquí solo se
 * construyen los adaptadores. El JSON del outbox y de los breakers usa un {@link JsonMapper} propio
 * del adaptador, no el de la API, para que cambiar cómo serializa la REST no cambie lo guardado.
 */
@Configuration(proxyBeanMethods = false)
@Profile("postgres")
public class PostgresAdaptersConfig {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean
    EntityManager sharedEntityManager(EntityManagerFactory emf) {
        return SharedEntityManagerCreator.createSharedEntityManager(emf);
    }

    @Bean
    JpaPaymentRepository paymentRepository(EntityManager em) {
        return new JpaPaymentRepository(em);
    }

    @Bean
    JdbcIdempotencyStore idempotencyStore(JdbcTemplate jdbc) {
        return new JdbcIdempotencyStore(jdbc);
    }

    @Bean
    JdbcCircuitBreakerStore circuitBreakerStore(JdbcTemplate jdbc, CircuitBreakerConfig config) {
        return new JdbcCircuitBreakerStore(jdbc, JSON, config);
    }

    @Bean
    JdbcProcessedNotificationStore processedNotificationStore(JdbcTemplate jdbc) {
        return new JdbcProcessedNotificationStore(jdbc);
    }

    @Bean
    OutboxEventPublisher eventPublisher(JdbcTemplate jdbc) {
        return new OutboxEventPublisher(jdbc, JSON);
    }

    @Bean
    JdbcOutboxReader outboxReader(JdbcTemplate jdbc) {
        return new JdbcOutboxReader(jdbc, JSON);
    }

    @Bean
    SpringTransactionRunner transactionRunner(PlatformTransactionManager transactionManager) {
        return new SpringTransactionRunner(new TransactionTemplate(transactionManager));
    }
}
