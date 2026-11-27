package dev.pedrogomez.payaf;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Comprueba que el contexto de Spring levanta con el perfil memory, sin base de datos.
 */
@SpringBootTest
@ActiveProfiles("memory")
class PayafApplicationTest {

    @Test
    void contextLoads() {
    }
}
