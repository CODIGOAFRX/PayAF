package dev.pedrogomez.payaf.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import dev.pedrogomez.archfixtures.payaf.adapter.in.rest.CleanPaymentController;
import dev.pedrogomez.archfixtures.payaf.adapter.in.rest.RestUsesJpaAdapterViolation;
import dev.pedrogomez.archfixtures.payaf.adapter.out.persistencejpa.CleanJpaPaymentRepository;
import dev.pedrogomez.archfixtures.payaf.adapter.out.persistencejpa.JpaUsesMemoryAdapterViolation;
import dev.pedrogomez.archfixtures.payaf.adapter.out.persistencememory.CleanInMemoryPaymentRepository;
import dev.pedrogomez.archfixtures.payaf.application.port.out.CleanPaymentRepository;
import dev.pedrogomez.archfixtures.payaf.application.service.ApplicationUsesAdapterViolation;
import dev.pedrogomez.archfixtures.payaf.application.service.ApplicationUsesJakartaViolation;
import dev.pedrogomez.archfixtures.payaf.application.service.ApplicationUsesSpringViolation;
import dev.pedrogomez.archfixtures.payaf.application.service.CleanAuthorizeService;
import dev.pedrogomez.archfixtures.payaf.domain.CleanMoney;
import dev.pedrogomez.archfixtures.payaf.domain.DomainUsesAdapterViolation;
import dev.pedrogomez.archfixtures.payaf.domain.DomainUsesApplicationViolation;
import dev.pedrogomez.archfixtures.payaf.domain.DomainUsesJackson3Violation;
import dev.pedrogomez.archfixtures.payaf.domain.DomainUsesJakartaPersistenceViolation;
import dev.pedrogomez.archfixtures.payaf.domain.DomainUsesSpringViolation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Demuestra que las reglas de {@link HexagonalArchitectureRules} detectan de verdad las
 * violaciones y no están en verde solo porque todavía no hay clases.
 * <p>
 * Cada caso importa con ClassFileImporter únicamente las fixtures implicadas (el origen
 * de la dependencia y su destino) y evalúa la misma regla que usa
 * {@link HexagonalArchitectureTest}, pero con la raíz de las fixtures.
 */
class HexagonalArchitectureRulesTest {

    private static final String FIXTURES_ROOT = "dev.pedrogomez.archfixtures.payaf";

    private static final ClassFileImporter IMPORTER = new ClassFileImporter();

    /** Todas las fixtures limpias: un hexágono en miniatura que cumple todas las reglas. */
    private static final Class<?>[] CLEAN_HEXAGON = {
            CleanMoney.class,
            CleanPaymentRepository.class,
            CleanAuthorizeService.class,
            CleanPaymentController.class,
            CleanJpaPaymentRepository.class,
            CleanInMemoryPaymentRepository.class
    };

    private static List<ArchRule> allRules() {
        return List.of(
                HexagonalArchitectureRules.domainIsFreeOfFrameworks(FIXTURES_ROOT),
                HexagonalArchitectureRules.domainDoesNotDependOnOuterLayers(FIXTURES_ROOT),
                HexagonalArchitectureRules.applicationDoesNotDependOnAdaptersOrSpring(FIXTURES_ROOT),
                HexagonalArchitectureRules.adaptersDoNotDependOnEachOther(FIXTURES_ROOT));
    }

    private static JavaClasses importWithCleanHexagon(Class<?>... violators) {
        Class<?>[] all = new Class<?>[CLEAN_HEXAGON.length + violators.length];
        System.arraycopy(CLEAN_HEXAGON, 0, all, 0, CLEAN_HEXAGON.length);
        System.arraycopy(violators, 0, all, CLEAN_HEXAGON.length, violators.length);
        return IMPORTER.importClasses(all);
    }

    /** Comprueba que la regla falla y que el informe señala la dependencia culpable. */
    private static void assertViolation(ArchRule rule, Class<?> violator, String offendingTarget) {
        EvaluationResult result = rule.evaluate(importWithCleanHexagon(violator));

        assertThat(result.hasViolation())
                .as("la regla «%s» debería fallar con %s", rule.getDescription(), violator.getSimpleName())
                .isTrue();
        assertThat(result.getFailureReport().getDetails())
                .as("el informe debe señalar %s -> %s", violator.getSimpleName(), offendingTarget)
                .anySatisfy(detail -> assertThat(detail)
                        .contains(violator.getName())
                        .contains(offendingTarget));
    }

    @Test
    @DisplayName("un hexágono que respeta las dependencias pasa todas las reglas")
    void cleanHexagonPassesEveryRule() {
        JavaClasses clean = IMPORTER.importClasses(CLEAN_HEXAGON);
        assertThat(clean).hasSize(CLEAN_HEXAGON.length);

        for (ArchRule rule : allRules()) {
            EvaluationResult result = rule.evaluate(clean);
            assertThat(result.hasViolation())
                    .as("falso positivo en «%s»: %s", rule.getDescription(), result.getFailureReport().getDetails())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("el dominio es Java puro")
    class DomainIsFreeOfFrameworks {

        private final ArchRule rule = HexagonalArchitectureRules.domainIsFreeOfFrameworks(FIXTURES_ROOT);

        @Test
        @DisplayName("falla si el dominio importa Spring")
        void failsWhenDomainImportsSpring() {
            assertViolation(rule, DomainUsesSpringViolation.class, "org.springframework.util.Assert");
        }

        @Test
        @DisplayName("falla si el dominio usa anotaciones de jakarta.persistence")
        void failsWhenDomainUsesJakartaPersistence() {
            assertViolation(rule, DomainUsesJakartaPersistenceViolation.class, "jakarta.persistence.Entity");
        }

        @Test
        @DisplayName("falla si el dominio usa Jackson 3 (tools.jackson)")
        void failsWhenDomainUsesJackson3() {
            assertViolation(rule, DomainUsesJackson3Violation.class, "tools.jackson.databind.ObjectMapper");
        }
    }

    @Nested
    @DisplayName("el dominio no conoce las capas externas")
    class DomainDoesNotDependOnOuterLayers {

        private final ArchRule rule = HexagonalArchitectureRules.domainDoesNotDependOnOuterLayers(FIXTURES_ROOT);

        @Test
        @DisplayName("falla si el dominio importa un puerto de la aplicación")
        void failsWhenDomainImportsApplication() {
            assertViolation(rule, DomainUsesApplicationViolation.class, CleanPaymentRepository.class.getName());
        }

        @Test
        @DisplayName("falla si el dominio importa un adaptador")
        void failsWhenDomainImportsAdapter() {
            assertViolation(rule, DomainUsesAdapterViolation.class, CleanInMemoryPaymentRepository.class.getName());
        }
    }

    @Nested
    @DisplayName("la aplicación solo conoce el dominio")
    class ApplicationDoesNotDependOnAdaptersOrSpring {

        private final ArchRule rule =
                HexagonalArchitectureRules.applicationDoesNotDependOnAdaptersOrSpring(FIXTURES_ROOT);

        @Test
        @DisplayName("falla si la aplicación importa un adaptador")
        void failsWhenApplicationImportsAdapter() {
            assertViolation(rule, ApplicationUsesAdapterViolation.class, CleanJpaPaymentRepository.class.getName());
        }

        @Test
        @DisplayName("falla si la aplicación usa anotaciones de Spring")
        void failsWhenApplicationUsesSpring() {
            assertViolation(rule, ApplicationUsesSpringViolation.class, "org.springframework.stereotype.Service");
        }

        @Test
        @DisplayName("falla si la aplicación usa JPA (jakarta.persistence)")
        void failsWhenApplicationUsesJakartaPersistence() {
            assertViolation(rule, ApplicationUsesJakartaViolation.class, "jakarta.persistence.EntityManager");
        }
    }

    @Nested
    @DisplayName("ningún adaptador importa otro")
    class AdaptersDoNotDependOnEachOther {

        private final ArchRule rule = HexagonalArchitectureRules.adaptersDoNotDependOnEachOther(FIXTURES_ROOT);

        @Test
        @DisplayName("falla si in.rest importa out.persistencejpa")
        void failsWhenRestImportsJpa() {
            assertViolation(rule, RestUsesJpaAdapterViolation.class, CleanJpaPaymentRepository.class.getName());
        }

        @Test
        @DisplayName("falla si out.persistencejpa importa out.persistencememory (mismo lado del hexágono)")
        void failsWhenJpaImportsMemory() {
            assertViolation(rule, JpaUsesMemoryAdapterViolation.class,
                    CleanInMemoryPaymentRepository.class.getName());
        }

        @Test
        @DisplayName("el informe nombra los dos adaptadores implicados")
        void reportNamesBothSlices() {
            EvaluationResult result = rule.evaluate(importWithCleanHexagon(JpaUsesMemoryAdapterViolation.class));

            assertThat(result.getFailureReport().toString())
                    .contains("adaptador out.persistencejpa")
                    .contains("adaptador out.persistencememory");
        }
    }

    @Test
    @DisplayName("las reglas del código real son las mismas que se prueban aquí")
    void realRulesAreBuiltFromTheSameFactory() {
        String fixtureDescription = HexagonalArchitectureRules.adaptersDoNotDependOnEachOther(FIXTURES_ROOT)
                .getDescription().replace(FIXTURES_ROOT, HexagonalArchitectureTest.ROOT);

        assertThat(HexagonalArchitectureTest.adapters_do_not_depend_on_each_other.getDescription())
                .isEqualTo(fixtureDescription);
        assertThat(HexagonalArchitectureTest.domain_is_free_of_frameworks.getDescription())
                .isEqualTo(HexagonalArchitectureRules.domainIsFreeOfFrameworks(FIXTURES_ROOT)
                        .getDescription().replace(FIXTURES_ROOT, HexagonalArchitectureTest.ROOT));
    }
}
