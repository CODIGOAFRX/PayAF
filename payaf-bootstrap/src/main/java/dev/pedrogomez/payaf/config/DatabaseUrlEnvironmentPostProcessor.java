package dev.pedrogomez.payaf.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Traduce una URL de Postgres en formato libpq ({@code postgres://usuario:clave@host/bd?sslmode=require})
 * a las tres propiedades que usa el perfil postgres: {@code PAYAF_DB_URL}, {@code PAYAF_DB_USER}
 * y {@code PAYAF_DB_PASSWORD}.
 * <p>
 * Neon (y casi cualquier Postgres gestionado) entrega la conexión en ese formato, pero JDBC
 * necesita {@code jdbc:postgresql://host/bd?...} con el usuario y la clave aparte. Se lee
 * {@code PAYAF_DATABASE_URL}. Si no está, no se hace nada y siguen valiendo las variables
 * {@code PAYAF_DB_*} o los valores por defecto del docker-compose local.
 * <p>
 * Conviene pasar la conexión directa (en Neon, {@code DATABASE_URL_UNPOOLED}) y no la del pooler:
 * la app ya tiene su propio pool (Hikari) y Flyway necesita una sesión estable para sus bloqueos.
 * <p>
 * Esto es configuración del arranque: el adaptador JPA no sabe de dónde viene la URL.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_VARIABLE = "PAYAF_DATABASE_URL";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = environment.getProperty(SOURCE_VARIABLE);
        if (raw == null || raw.isBlank()) {
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource("payafDatabaseUrl", translate(clean(raw))));
    }

    /**
     * Quita espacios, comillas y el BOM (U+FEFF) que algunas herramientas de Windows añaden al
     * guardar el secreto: {@code trim()} no lo considera espacio y rompería el esquema.
     */
    static String clean(String raw) {
        String value = raw.replace("﻿", "").trim();
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    static Map<String, Object> translate(String raw) {
        URI uri = URI.create(raw);
        String scheme = uri.getScheme();
        if (!"postgres".equals(scheme) && !"postgresql".equals(scheme)) {
            throw new IllegalStateException(SOURCE_VARIABLE + " tiene que empezar por postgres:// o postgresql://");
        }
        String userInfo = uri.getRawUserInfo();
        if (userInfo == null || !userInfo.contains(":")) {
            throw new IllegalStateException(SOURCE_VARIABLE + " tiene que incluir usuario y clave");
        }
        int colon = userInfo.indexOf(':');
        String user = decode(userInfo.substring(0, colon));
        String password = decode(userInfo.substring(colon + 1));

        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0) {
            jdbc.append(':').append(uri.getPort());
        }
        jdbc.append(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            jdbc.append('?').append(uri.getRawQuery());
        }

        Map<String, Object> props = new HashMap<>();
        props.put("PAYAF_DB_URL", jdbc.toString());
        props.put("PAYAF_DB_USER", user);
        props.put("PAYAF_DB_PASSWORD", password);
        return props;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    @Override
    public int getOrder() {
        // Después de leer las variables de entorno y antes de resolver la configuración.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
