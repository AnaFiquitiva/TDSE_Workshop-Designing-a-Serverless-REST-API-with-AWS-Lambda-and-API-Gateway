package edu.eci.tdse.orders;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the main architectural rule of the workshop: the domain package is ordinary Java and does
 * not know about AWS, API Gateway or HTTP. Same for the direct (non-proxy) handlers, which rely on
 * the Lambda runtime to do all JSON conversion.
 */
class ArchitectureTest {

    private static final List<String> FORBIDDEN = List.of(
            "import com.amazonaws", "import software.amazon", "import com.fasterxml",
            "import java.net.http", "import jakarta.servlet", "import javax.servlet",
            "import org.springframework");

    @Test
    void domainDoesNotDependOnInfrastructure() throws IOException {
        assertNoForbiddenImports(Path.of("src/main/java/edu/eci/tdse/orders/domain"));
    }

    @Test
    void directHandlersDoNotDependOnAwsLibraries() throws IOException {
        assertNoForbiddenImports(Path.of("src/main/java/edu/eci/tdse/orders/lambda/direct"));
    }

    private static void assertNoForbiddenImports(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (String forbidden : FORBIDDEN) {
                    assertTrue(!source.contains(forbidden), file + " contains '" + forbidden + "'");
                }
            }
        }
    }
}
