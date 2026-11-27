package dev.pedrogomez.payaf.config;

import dev.pedrogomez.payaf.application.port.in.RelayOutboxUseCase;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Lanza el relay del outbox ({@link RelayOutboxUseCase}) cada {@value #INTERVAL_MS} ms, en los dos
 * perfiles: en {@code postgres} lee la tabla {@code outbox}; en {@code memory}, la cola del
 * {@code InMemoryEventPublisher}. Se apaga con {@code payaf.outbox.relay.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "payaf.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    static final long INTERVAL_MS = 500;
    static final int BATCH = 200;

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final RelayOutboxUseCase relay;

    public OutboxRelayScheduler(RelayOutboxUseCase relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelay = INTERVAL_MS, timeUnit = TimeUnit.MILLISECONDS)
    public void relay() {
        try {
            relay.relay(BATCH);
        } catch (RuntimeException e) {
            LOG.warn("El relay del outbox ha fallado; se reintentará en la próxima pasada", e);
        }
    }
}
