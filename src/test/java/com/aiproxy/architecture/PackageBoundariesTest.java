package com.aiproxy.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageBoundariesTest {
    @Test
    void sharedLayersAndProvidersRespectTheirDependencyBoundaries() throws Exception {
        Path root = Path.of("src/main/java/com/aiproxy");
        var dependencies = Pattern.compile("com\\.aiproxy\\.(provider\\.(codex|anthropic|copilot)|server)\\b");
        var violations = new ArrayList<String>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                String source = Files.readString(path);
                var matches = dependencies.matcher(source);
                boolean shared = relative.startsWith("protocol/") || relative.startsWith("model/")
                        || relative.startsWith("sse/") || relative.startsWith("provider/spi/")
                        || relative.startsWith("state/");
                while (matches.find()) {
                    String provider = matches.group(2);
                    if (shared || (relative.startsWith("provider/") && provider != null
                            && !relative.startsWith("provider/" + provider + "/"))
                            || (relative.startsWith("server/") && provider != null)) {
                        violations.add(relative + " depends on " + matches.group());
                    }
                }
                var declaration = Pattern.compile("package ([\\w.]+);").matcher(source);
                if (!declaration.find() || !declaration.group(1).equals("com.aiproxy"
                        + (relative.contains("/") ? "." + relative.substring(0, relative.lastIndexOf('/')).replace('/', '.') : ""))) {
                    violations.add(relative + " does not match its package declaration");
                }
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }
}
