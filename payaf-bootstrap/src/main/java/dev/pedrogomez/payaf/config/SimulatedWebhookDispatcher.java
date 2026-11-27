package dev.pedrogomez.payaf.config;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook.SimulatedWebhook;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook.WebhookSink;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;

/**
 * Envía por HTTP a la propia aplicación ({@code POST http://localhost:<puerto>/webhooks/{gateway}})
 * los webhooks que emite el simulador, con un retraso aleatorio de 1 a 2 s, como haría una PSP real.
 * <p>
 * Es infraestructura de la demo y vive en bootstrap: el simulador solo deja la notificación firmada
 * en un {@link WebhookSink} y no sabe de HTTP ni de puertos. Si no hay servidor web (la CLI o un test
 * sin puerto), se descartan. Solo se crea con {@code payaf.sim.webhooks.enabled=true}.
 */
class SimulatedWebhookDispatcher implements WebhookSink, ApplicationListener<WebServerInitializedEvent>,
        DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(SimulatedWebhookDispatcher.class);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "payaf-sim-webhooks");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private volatile int port = -1;

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        port = event.getWebServer().getPort();
    }

    @Override
    public void enqueue(SimulatedWebhook webhook) {
        if (port <= 0) {
            LOG.debug("Sin servidor web: se descarta el webhook simulado de {}", webhook.gateway());
            return;
        }
        long delay = ThreadLocalRandom.current().nextLong(1000, 2001);
        scheduler.schedule(() -> send(webhook), delay, TimeUnit.MILLISECONDS);
    }

    private void send(SimulatedWebhook webhook) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/webhooks/" + webhook.gateway()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", webhook.contentType())
                    .POST(HttpRequest.BodyPublishers.ofString(webhook.body()));
            webhook.headers().forEach(request::header);
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            LOG.info("Webhook simulado de {} → {} {}", webhook.gateway(), response.statusCode(), response.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("No se ha podido entregar el webhook simulado de {}: {}", webhook.gateway(), e.toString());
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
