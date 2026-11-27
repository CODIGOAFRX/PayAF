package dev.pedrogomez.payaf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de arranque de PayAF.
 * <p>
 * Es la única pieza que conoce a la vez la aplicación y todos los adaptadores: aquí
 * (y en las clases de configuración de este módulo) se cablea el hexágono con Spring.
 */
@SpringBootApplication
public class PayafApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayafApplication.class, args);
    }
}
