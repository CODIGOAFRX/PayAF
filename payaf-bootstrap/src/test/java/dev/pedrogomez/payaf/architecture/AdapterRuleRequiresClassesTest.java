package dev.pedrogomez.payaf.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Desde la fase 4a la regla de los adaptadores ya no lleva {@code allowEmptyShould}: tiene que
 * ver los adaptadores reales y fallar si no encontrara ninguno.
 */
class AdapterRuleRequiresClassesTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(HexagonalArchitectureTest.ROOT);

    @Test
    @DisplayName("la regla de adaptadores falla si no encuentra ninguna clase")
    void failsOnEmpty() {
        assertThatThrownBy(() -> HexagonalArchitectureRules.adaptersDoNotDependOnEachOther("dev.pedrogomez.nowhere")
                .check(PRODUCTION))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("failed to check any classes");
    }

    @Test
    @DisplayName("con el código real ve el simulador de PSP y la persistencia en memoria, y pasa")
    void seesRealAdapters() {
        assertThat(PRODUCTION.contain(
                "dev.pedrogomez.payaf.adapter.out.gatewaysim.stripe.StripePaymentGateway")).isTrue();
        assertThat(PRODUCTION.contain(
                "dev.pedrogomez.payaf.adapter.out.persistencememory.InMemoryPaymentRepository")).isTrue();

        HexagonalArchitectureRules.adaptersDoNotDependOnEachOther(HexagonalArchitectureTest.ROOT).check(PRODUCTION);
    }
}
