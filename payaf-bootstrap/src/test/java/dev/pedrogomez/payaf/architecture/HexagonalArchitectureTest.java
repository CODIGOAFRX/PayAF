package dev.pedrogomez.payaf.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Reglas de dependencia del hexágono. Se ejecuta desde bootstrap porque es el único
 * módulo que tiene en el classpath el dominio, la aplicación y todos los adaptadores.
 * <p>
 * Las reglas viven en {@link HexagonalArchitectureRules} para poder comprobar, con
 * fixtures, que de verdad detectan las violaciones ({@link HexagonalArchitectureRulesTest}).
 * Las reglas del dominio y de la aplicación exigen encontrar clases; la de los adaptadores
 * se permite vacía ({@code allowEmptyShould}) mientras esa capa no tenga código.
 */
@AnalyzeClasses(packages = HexagonalArchitectureTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {

    static final String ROOT = "dev.pedrogomez.payaf";

    @ArchTest
    static final ArchRule domain_is_free_of_frameworks =
            HexagonalArchitectureRules.domainIsFreeOfFrameworks(ROOT);

    @ArchTest
    static final ArchRule domain_does_not_depend_on_outer_layers =
            HexagonalArchitectureRules.domainDoesNotDependOnOuterLayers(ROOT);

    @ArchTest
    static final ArchRule application_does_not_depend_on_adapters_or_spring =
            HexagonalArchitectureRules.applicationDoesNotDependOnAdaptersOrSpring(ROOT);

    @ArchTest
    static final ArchRule adapters_do_not_depend_on_each_other =
            HexagonalArchitectureRules.adaptersDoNotDependOnEachOther(ROOT);
}
