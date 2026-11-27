package dev.pedrogomez.payaf.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La regla de la aplicación de {@link HexagonalArchitectureTest} tiene que comprobar clases de
 * verdad: si no encontrara ninguna (paquete mal escrito, módulo fuera del classpath) debe
 * fallar, no pasar en verde sin mirar nada.
 */
class ApplicationRuleRequiresClassesTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(HexagonalArchitectureTest.ROOT);

    @Test
    @DisplayName("la regla de application falla si no encuentra ninguna clase (no lleva allowEmptyShould)")
    void failsOnEmpty() {
        ArchRule rule = HexagonalArchitectureRules.applicationDoesNotDependOnAdaptersOrSpring("dev.pedrogomez.nowhere");

        assertThatThrownBy(() -> rule.check(PRODUCTION))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("failed to check any classes");
    }

    @Test
    @DisplayName("con el código real la regla ve los servicios y puertos de application y pasa")
    void seesRealApplicationClasses() {
        long applicationClasses = PRODUCTION.stream()
                .map(JavaClass::getPackageName)
                .filter(p -> p.startsWith(HexagonalArchitectureTest.ROOT + ".application"))
                .count();
        assertThat(applicationClasses).isGreaterThan(30);
        assertThat(PRODUCTION.contain("dev.pedrogomez.payaf.application.service.AuthorizePaymentService")).isTrue();

        HexagonalArchitectureRules.applicationDoesNotDependOnAdaptersOrSpring(HexagonalArchitectureTest.ROOT)
                .check(PRODUCTION);
    }
}
