package io.github.hvmello.aienglab.toolcalling;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Keeps library imports where ADR 0001 and 0002 say they belong. */
class ArchitectureTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static List<String> importers(String prefix) throws IOException {
        return sources().stream()
                .filter(p -> {
                    try {
                        return Files.readAllLines(p).stream()
                                .anyMatch(line -> line.startsWith("import " + prefix)
                                        || line.startsWith("import static " + prefix));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .map(p -> p.getFileName().toString())
                .toList();
    }

    @Test
    void onlyTheSpringAdapterImportsSpring() throws IOException {
        assertThat(importers("org.springframework")).containsExactly("SpringAiLlmClient.java");
    }

    @Test
    void nothingInThisModuleImportsLangChain4j() throws IOException {
        assertThat(importers("dev.langchain4j")).isEmpty();
    }

    @Test
    void theSourcesAreFound() throws IOException {
        assertThat(sources()).isNotEmpty();
    }
}
