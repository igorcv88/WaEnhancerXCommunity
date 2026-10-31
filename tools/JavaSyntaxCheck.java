import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.util.Arrays;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Parses Java syntax only; NOT Android compilation, linkage, unit tests or AGSL validation. */
public final class JavaSyntaxCheck {
    public static void main(String[] paths) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("jdk.compiler module unavailable");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> files = manager.getJavaFileObjectsFromStrings(Arrays.asList(paths));
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    Arrays.asList("-proc:none", "-source", "17"), null, files);
            task.parse();
            int errors = 0;
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    System.err.println(diagnostic);
                    errors++;
                }
            }
            if (errors != 0) throw new AssertionError(errors + " Java syntax errors");
        }
        System.out.println("PASS JavaSyntaxCheck: " + paths.length + " sources parsed");
    }
}
