package dev.pedrogomez.payaf.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class DatabaseUrlEnvironmentPostProcessorTest {

    @Test
    void traduceUnaUrlDeNeonAJdbc() {
        Map<String, Object> props = DatabaseUrlEnvironmentPostProcessor.translate(
                "postgresql://neondb_owner:p%40ss@ep-x-123.eu-central-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require");

        assertThat(props).containsEntry("PAYAF_DB_URL",
                "jdbc:postgresql://ep-x-123.eu-central-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require");
        assertThat(props).containsEntry("PAYAF_DB_USER", "neondb_owner");
        assertThat(props).containsEntry("PAYAF_DB_PASSWORD", "p@ss");
    }

    @Test
    void limpiaBomYComillas() {
        assertThat(DatabaseUrlEnvironmentPostProcessor.clean("﻿\"postgres://u:p@h/db\"\r\n"))
                .isEqualTo("postgres://u:p@h/db");
    }

    @Test
    void conservaElPuerto() {
        Map<String, Object> props = DatabaseUrlEnvironmentPostProcessor.translate("postgres://u:p@localhost:5433/payaf");
        assertThat(props).containsEntry("PAYAF_DB_URL", "jdbc:postgresql://localhost:5433/payaf");
    }

    @Test
    void rechazaOtrosEsquemasYUrlsSinClave() {
        assertThatThrownBy(() -> DatabaseUrlEnvironmentPostProcessor.translate("mysql://u:p@h/db"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DatabaseUrlEnvironmentPostProcessor.translate("postgres://h/db"))
                .isInstanceOf(IllegalStateException.class);
    }
}
