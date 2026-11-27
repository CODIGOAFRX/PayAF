package dev.pedrogomez.payaf.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El dominio solo puede importar {@code java.*} y a sí mismo.
 * <p>
 * Complementa al ArchUnit de bootstrap, que prohíbe una lista negra (Spring, jakarta,
 * Jackson): este test es una lista blanca, así que también caza cualquier librería nueva
 * (Guava, Apache Commons, Lombok...). Lee los fuentes de {@code src/main/java} porque en
 * este módulo no hay ArchUnit, y exige encontrar ficheros para no pasar en vacío.
 */
class DomainPurityTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)", Pattern.MULTILINE);
    /** Nombres totalmente cualificados usados sin import, p. ej. {@code @org.springframework.Foo}. */
    private static final Pattern QUALIFIED = Pattern.compile("\\b(?:org|com|jakarta|javax|lombok|io|net)\\.[a-z]\\w*\\.");

    @Test
    @DisplayName("payaf-domain solo importa java.* y dev.pedrogomez.payaf.domain.*")
    void domainOnlyImportsJavaAndItself() throws IOException {
        List<Path> sources;
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            sources = files.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(sources).as("hay fuentes del dominio que revisar").hasSizeGreaterThan(30);

        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            String code = Files.readString(source, StandardCharsets.UTF_8);
            Matcher imports = IMPORT.matcher(code);
            while (imports.find()) {
                String imported = imports.group(1);
                if (!imported.startsWith("java.") && !imported.startsWith("dev.pedrogomez.payaf.domain.")) {
                    violations.add(source + ": import " + imported);
                }
            }
            String withoutComments = code.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
            Matcher qualified = QUALIFIED.matcher(withoutComments);
            while (qualified.find()) {
                violations.add(source + ": nombre cualificado " + qualified.group());
            }
        }
        assertThat(violations).isEmpty();
    }
}
