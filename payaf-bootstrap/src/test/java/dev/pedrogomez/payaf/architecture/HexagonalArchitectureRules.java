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
 * <p>
 * Ninguna regla admite estar vacía: el dominio tiene clases desde la fase 2, la aplicación
 * desde la fase 3 y los adaptadores (simulador de PSP y persistencia en memoria) desde la 4a.
 * Si un día una regla no encontrara ninguna (por un paquete mal escrito, por ejemplo), debe
 * fallar en lugar de pasar sin comprobar nada. Las fixtures siempre incluyen una clase de
 * dominio ({@code CleanMoney}), dos de aplicación ({@code CleanPaymentRepository},
 * {@code CleanAuthorizeService}) y tres adaptadores ({@code CleanPaymentController},
 * {@code CleanJpaPaymentRepository}, {@code CleanInMemoryPaymentRepository}), así que no les
 * afecta.
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
                .because("el dominio es Java puro");
    }

    static ArchRule domainDoesNotDependOnOuterLayers(String root) {
        return noClasses()
                .that().resideInAPackage(domain(root))
                .should().dependOnClassesThat().resideInAnyPackage(application(root), adapter(root))
                .because("el dominio es el centro del hexágono y no conoce las capas externas");
    }

    static ArchRule applicationDoesNotDependOnAdaptersOrSpring(String root) {
        return classes()
                .that().resideInAPackage(application(root))
                .should().onlyDependOnClassesThat().resideInAnyPackage(application(root), domain(root), "java..")
                .because("la aplicación solo conoce el dominio, sus propios puertos y el JDK");
    }

    static ArchRule adaptersDoNotDependOnEachOther(String root) {
        return slices()
                .matching(root + ".adapter.(*).(*)..")
                .namingSlices("adaptador $1.$2")
                .should().notDependOnEachOther()
                .because("un adaptador nunca importa otro adaptador");
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
