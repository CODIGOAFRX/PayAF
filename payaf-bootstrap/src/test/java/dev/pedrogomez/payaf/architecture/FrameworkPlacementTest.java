package dev.pedrogomez.payaf.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Jackson y Spring solo en los adaptadores que los necesitan y en bootstrap. El dominio y la
 * aplicación ya lo vigila {@link HexagonalArchitectureTest}; aquí, los adaptadores que deben seguir
 * siendo Java puro (la CLI solo usa picocli; el broadcaster del outbox, nada).
 */
class FrameworkPlacementTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.pedrogomez.payaf");

    @Test
    @DisplayName("la CLI y el broadcaster del outbox no dependen de Spring, Jakarta ni Jackson")
    void pureAdapters() {
        noClasses().that().resideInAnyPackage("dev.pedrogomez.payaf.adapter.in.cli..",
                        "dev.pedrogomez.payaf.adapter.out.outbox..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..",
                        "com.fasterxml..", "tools.jackson..")
                .allowEmptyShould(false)
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("el simulador y la memoria tampoco usan Jackson")
    void noJacksonInSimOrMemory() {
        noClasses().that().resideInAnyPackage("dev.pedrogomez.payaf.adapter.out.gatewaysim..",
                        "dev.pedrogomez.payaf.adapter.out.persistencememory..")
                .should().dependOnClassesThat().resideInAnyPackage("com.fasterxml..", "tools.jackson..")
                .allowEmptyShould(false)
                .check(PRODUCTION);
    }
}
