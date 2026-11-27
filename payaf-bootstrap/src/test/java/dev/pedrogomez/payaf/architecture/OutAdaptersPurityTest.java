package dev.pedrogomez.payaf.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tester (fase 4a): el simulador de PSP y la persistencia en memoria son Java puro. No llevan
 * anotaciones ni tipos de Spring ni de Jakarta, y no se importan entre sí.
 */
class OutAdaptersPurityTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.pedrogomez.payaf");

    private static final String SIM = "dev.pedrogomez.payaf.adapter.out.gatewaysim..";
    private static final String MEMORY = "dev.pedrogomez.payaf.adapter.out.persistencememory..";

    @Test
    @DisplayName("gateway-sim y persistence-memory no dependen de Spring ni de Jakarta (tampoco por anotaciones)")
    void noFrameworks() {
        noClasses().that().resideInAnyPackage(SIM, MEMORY)
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..", "javax..")
                .because("los adaptadores de la fase 4a son Java puro; los cablea bootstrap")
                .check(PRODUCTION);

        for (JavaClass c : PRODUCTION) {
            if (c.getPackageName().startsWith("dev.pedrogomez.payaf.adapter.out.gatewaysim")
                    || c.getPackageName().startsWith("dev.pedrogomez.payaf.adapter.out.persistencememory")) {
                assertThat(c.getAnnotations())
                        .as("anotaciones de %s", c.getName())
                        .noneMatch(a -> a.getRawType().getPackageName().startsWith("org.springframework"));
            }
        }
    }

    @Test
    @DisplayName("gateway-sim y persistence-memory no se importan entre sí ni importan bootstrap")
    void noCrossImports() {
        noClasses().that().resideInAPackage(SIM)
                .should().dependOnClassesThat().resideInAnyPackage(MEMORY, "dev.pedrogomez.payaf.config..")
                .check(PRODUCTION);
        noClasses().that().resideInAPackage(MEMORY)
                .should().dependOnClassesThat().resideInAnyPackage(SIM, "dev.pedrogomez.payaf.config..")
                .check(PRODUCTION);
        assertThat(PRODUCTION.stream().filter(c -> c.getPackageName().startsWith(
                "dev.pedrogomez.payaf.adapter.out.gatewaysim"))).hasSizeGreaterThan(10);
        assertThat(PRODUCTION.stream().filter(c -> c.getPackageName().startsWith(
                "dev.pedrogomez.payaf.adapter.out.persistencememory"))).hasSizeGreaterThan(5);
    }
}
