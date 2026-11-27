package dev.pedrogomez.payaf;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Arranca la aplicación de verdad (servidor HTTP en un puerto aleatorio) SIN indicar
 * perfil, para comprobar que el perfil por defecto es memory, que no se crea ninguna
 * base de datos y que el health del actuator responde UP.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class DefaultProfileHealthTest {

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext context;

    @Test
    void defaultProfileIsMemory() {
        assertThat(environment.getActiveProfiles()).isEmpty();
        assertThat(environment.getDefaultProfiles()).containsExactly("memory");
    }

    @Test
    void memoryProfileDoesNotCreateADataSource() {
        assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
    }

    @Test
    void actuatorHealthIsUp() throws Exception {
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }
}
