import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;

/** Run with `java tools/CoreTestLauncher.java`; no Gradle or downloads needed. */
class CoreTestLauncher {
    public static void main(String[] args) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK 17+ with jdk.compiler is required");
        var output = Files.createTempDirectory("pod4me-core-tests-");
        try {
            var sources = new ArrayList<String>();
            sources.addAll(List.of("-source", "17", "-target", "17", "-Xlint:-options", "-d", output.toString()));
            try (var paths = Files.walk(Path.of("app/src/main/java/com/pod4me/domain"))) {
                paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> sources.add(p.toString()));
            }
            sources.add("app/src/test/java/com/pod4me/domain/CoreChecks.java");
            if (compiler.run(null, null, null, sources.toArray(new String[0])) != 0) throw new AssertionError("Java compilation failed");
            try (var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()})) {
                loader.loadClass("com.pod4me.domain.CoreChecks").getMethod("run").invoke(null);
            }
        } finally {
            try (var paths = Files.walk(output)) { for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
    }
}
