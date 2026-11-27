package dev.pedrogomez.payaf.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Reglas de dependencia del hexágono, parametrizadas por el paquete raíz.
 * <p>
 * {@link HexagonalArchitectureTest} las aplica sobre el código real
 * ({@code dev.pedrogomez.payaf}) y {@link HexagonalArchitectureRulesTest} las aplica
 * sobre clases fixture con la misma estructura de paquetes para demostrar que cada
 * regla falla cuando se viola. Así las dos suites evalúan exactamente la misma regla.
 */
final class HexagonalArchitectureRules {

    private HexagonalArchitectureRules() {
    }

    static ArchRule domainIsFreeOfFrameworks(String root) {
        return noClasses()
                .that().resideInAPackage(domain(root))
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta..", "javax.persistence..",
                        "com.fasterxml..", "tools.jackson..")
                .because("el dominio es Java puro")
                .allowEmptyShould(true);
    }

    static ArchRule domainDoesNotDependOnOuterLayers(String root) {
        return noClasses()
                .that().resideInAPackage(domain(root))
                .should().dependOnClassesThat().resideInAnyPackage(application(root), adapter(root))
                .because("el dominio es el centro del hexágono y no conoce las capas externas")
                .allowEmptyShould(true);
    }

    static ArchRule applicationDoesNotDependOnAdaptersOrSpring(String root) {
        return classes()
                .that().resideInAPackage(application(root))
                .should().onlyDependOnClassesThat().resideInAnyPackage(application(root), domain(root), "java..")
                .because("la aplicación solo conoce el dominio, sus propios puertos y el JDK")
                .allowEmptyShould(true);
    }

    static ArchRule adaptersDoNotDependOnEachOther(String root) {
        return slices()
                .matching(root + ".adapter.(*).(*)..")
                .namingSlices("adaptador $1.$2")
                .should().notDependOnEachOther()
                .because("un adaptador nunca importa otro adaptador")
                .allowEmptyShould(true);
    }

    private static String domain(String root) {
        return root + ".domain..";
    }

    private static String application(String root) {
        return root + ".application..";
    }

    private static String adapter(String root) {
        return root + ".adapter..";
    }
}
