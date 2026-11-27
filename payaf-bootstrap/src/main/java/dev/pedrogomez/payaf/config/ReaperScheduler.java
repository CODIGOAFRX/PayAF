package dev.pedrogomez.payaf.config;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.GatewaySimulator;
import dev.pedrogomez.payaf.application.port.in.ReapReport;
import dev.pedrogomez.payaf.application.port.in.ReapStalePaymentsUseCase;
import dev.pedrogomez.payaf.application.port.out.GatewayCatalog;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Lanza el reaper de pagos huérfanos ({@link ReapStalePaymentsUseCase}) cada
 * {@value #INTERVAL_SECONDS} s, con una antigüedad mínima de 2 minutos.
 * <p>
 * <strong>Por qué el umbral tiene que ser mucho mayor que un enrutado.</strong> Mientras un pago
 * prueba pasarelas no se guarda nada: su {@code updatedAt} guardado sigue siendo el de su
 * creación hasta que se cierra. El reaper no puede distinguir un pago en vuelo de uno huérfano;
 * solo puede confiar en que ningún enrutado vivo dure tanto como su umbral. Un enrutado dura
 * como mucho, aproximadamente, nº de pasarelas × timeout (cada una puede agotar su tiempo de
 * espera antes del failover). Con 4 pasarelas y 2 s son unos 8 s, frente a los 2 minutos de
 * {@link #OLDER_THAN}. Si aun así coincidieran, la versión optimista decide quién gana (ver
 * {@code ReapStalePaymentsService}), pero con un margen así no debería pasar.
 * <p>
 * Por eso, al arrancar, se exige {@code olderThan ≥ }{@value #SAFETY_FACTOR}{@code  × (nº de
 * pasarelas × timeout)} y, si no se cumple, la aplicación no arranca (ver
 * {@link #requireSafeThreshold}). Así nadie puede subir timeouts o añadir pasarelas y dejar el
 * reaper matando pagos que siguen vivos.
 * <p>
 * El planificador es infraestructura y por eso vive aquí, en bootstrap; el caso de uso no sabe
 * quién ni cada cuánto lo llama. Se puede apagar con {@code payaf.reaper.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "payaf.reaper.enabled", havingValue = "true", matchIfMissing = true)
public class ReaperScheduler {

    static final long INTERVAL_SECONDS = 30;
    static final Duration OLDER_THAN = Duration.ofMinutes(2);
    /** Margen exigido entre el umbral del reaper y la duración máxima de un enrutado. */
    static final int SAFETY_FACTOR = 4;

    private static final Logger LOG = LoggerFactory.getLogger(ReaperScheduler.class);

    private final ReapStalePaymentsUseCase reaper;

    public ReaperScheduler(ReapStalePaymentsUseCase reaper, GatewayCatalog catalog, GatewaySimulator simulator) {
        this.reaper = reaper;
        requireSafeThreshold(OLDER_THAN, catalog.profiles().size(), simulator.timeout());
    }

    /**
     * @throws IllegalStateException si {@code olderThan} es menor que {@value #SAFETY_FACTOR} ×
     *                               ({@code gateways} × {@code timeout})
     */
    static void requireSafeThreshold(Duration olderThan, int gateways, Duration timeout) {
        Duration longestRouting = timeout.multipliedBy(gateways);
        Duration required = longestRouting.multipliedBy(SAFETY_FACTOR);
        if (olderThan.compareTo(required) < 0) {
            throw new IllegalStateException("El umbral del reaper (" + olderThan + ") es demasiado bajo: un "
                    + "enrutado puede durar hasta " + longestRouting + " (" + gateways + " pasarelas × " + timeout
                    + " de timeout) y el umbral debe ser al menos " + SAFETY_FACTOR + " veces eso (" + required
                    + "). Si no, el reaper daría por abandonados pagos que aún se están enrutando.");
        }
    }

    @Scheduled(fixedDelay = INTERVAL_SECONDS, initialDelay = INTERVAL_SECONDS, timeUnit = TimeUnit.SECONDS)
    public void reap() {
        try {
            ReapReport report = reaper.reap(OLDER_THAN);
            if (report.total() > 0) {
                LOG.info("Reaper: {} pagos abandonados y {} recuperados", report.abandoned(), report.recovered());
            }
        } catch (RuntimeException e) {
            LOG.warn("El reaper de pagos huérfanos ha fallado; se reintentará en la próxima pasada", e);
        }
    }
}
