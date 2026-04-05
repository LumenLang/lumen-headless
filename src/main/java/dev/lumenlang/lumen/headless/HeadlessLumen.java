package dev.lumenlang.lumen.headless;

import dev.lumenlang.lumen.api.LumenAPI;
import dev.lumenlang.lumen.api.LumenProvider;
import dev.lumenlang.lumen.api.scanner.RegistrationScanner;
import dev.lumenlang.lumen.api.version.MinecraftVersion;
import dev.lumenlang.lumen.pipeline.addon.AddonManager;
import dev.lumenlang.lumen.pipeline.addon.LumenAPIImpl;
import dev.lumenlang.lumen.pipeline.addon.ScriptBinderManager;
import dev.lumenlang.lumen.pipeline.binder.ScriptBinder;
import dev.lumenlang.lumen.pipeline.codegen.CodegenContext;
import dev.lumenlang.lumen.pipeline.codegen.TypeEnv;
import dev.lumenlang.lumen.pipeline.events.EventDefRegistry;
import dev.lumenlang.lumen.pipeline.inject.InjectableHandlers;
import dev.lumenlang.lumen.pipeline.java.JavaBuilder;
import dev.lumenlang.lumen.pipeline.java.compiled.ClassBuilder;
import dev.lumenlang.lumen.pipeline.java.compiler.system.SourceFile;
import dev.lumenlang.lumen.pipeline.language.emit.CodeEmitter;
import dev.lumenlang.lumen.pipeline.language.emit.EmitRegistry;
import dev.lumenlang.lumen.pipeline.language.emit.TransformerRegistry;
import dev.lumenlang.lumen.pipeline.language.pattern.PatternRegistry;
import dev.lumenlang.lumen.pipeline.logger.LumenLogger;
import dev.lumenlang.lumen.pipeline.typebinding.TypeRegistry;
import dev.lumenlang.lumen.plugin.defaults.type.BuiltinTypeBindings;
import dev.lumenlang.lumen.plugin.inject.InjectableHandlerFactoryImpl;
import dev.lumenlang.lumen.plugin.scanner.RegistrationScannerBackend;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.logging.Logger;

/**
 * Headless Lumen bootstrap and CLI for validating Lumen scripts without
 * a running Minecraft server. All pattern, type, event, and emit registrations
 * are loaded from the plugin defaults using spigot-api on the classpath.
 */
public final class HeadlessLumen {

    private static final Logger LOGGER = Logger.getLogger("HeadlessLumen");

    private final PatternRegistry patternRegistry;
    private final TypeRegistry typeRegistry;

    /**
     * Bootstraps the Lumen registration system, loading all builtin patterns,
     * types, events, and emitters without a running Minecraft server.
     */
    public HeadlessLumen() {
        HeadlessBukkitServer.install();
        LumenLogger.init(LOGGER);
        MinecraftVersion.detect("1.20");
        InjectableHandlers.factory(new InjectableHandlerFactoryImpl());

        typeRegistry = new TypeRegistry();
        BuiltinTypeBindings.register(typeRegistry);
        patternRegistry = new PatternRegistry(typeRegistry);
        PatternRegistry.instance(patternRegistry);

        EmitRegistry emitRegistry = new EmitRegistry();
        EmitRegistry.instance(emitRegistry);

        TransformerRegistry transformerRegistry = new TransformerRegistry();
        TransformerRegistry.instance(transformerRegistry);

        ScriptBinderManager binderManager = new ScriptBinderManager();
        ScriptBinder.init(binderManager);

        LumenAPI api = new LumenAPIImpl(patternRegistry, typeRegistry, emitRegistry, transformerRegistry, binderManager);

        AddonManager addonManager = new AddonManager();
        LumenProvider.init(api, addonManager::registerAddon);

        RegistrationScanner.init(new RegistrationScannerBackend(api));
        RegistrationScanner.scan("dev.lumenlang.lumen.plugin.defaults");

        LOGGER.info("Bootstrap complete: " + patternCount() + " patterns, " + typeRegistry.allBindings().size() + " types, " + EventDefRegistry.defs().size() + " events");
    }

    /**
     * CLI entry point. Supports two modes:
     * <ul>
     *   <li>{@code --server} starts a JSON line protocol on stdin/stdout</li>
     *   <li>{@code <script.luma>} validates and compiles a single script file</li>
     * </ul>
     */
    public static void main(@NotNull String[] args) {
        if (args.length < 1) {
            System.err.println("Usage:");
            System.err.println("  java -jar LumenHeadless.jar <script.luma>");
            System.err.println("  java -jar LumenHeadless.jar --server");
            System.exit(1);
        }

        if (args[0].equals("--server")) {
            JsonProtocol.run(new HeadlessLumen());
            return;
        }

        runCli(Path.of(args[0]));
    }

    /**
     * Runs single-file CLI validation and compilation.
     */
    private static void runCli(@NotNull Path scriptPath) {
        if (!Files.exists(scriptPath)) {
            System.err.println("File not found: " + scriptPath);
            System.exit(1);
        }

        HeadlessLumen headless = new HeadlessLumen();
        String source;
        try {
            source = Files.readString(scriptPath);
        } catch (IOException e) {
            System.err.println("Failed to read file: " + e.getMessage());
            System.exit(1);
            return;
        }

        String name = scriptPath.getFileName().toString();
        try {
            String java = headless.compile(source, name);
            System.out.println("Validation successful for: " + name);
            System.out.println();
            System.out.println("Generated Java output:");
            System.out.println("=".repeat(60));
            System.out.println(java);
            System.out.println("=".repeat(60));

            List<CompileError> errors = headless.compileJava(java, "dev.lumenlang.lumen.java.compiled." + ClassBuilder.normalize(name));
            if (errors.isEmpty()) {
                System.out.println("Java compilation: SUCCESS");
            } else {
                System.err.println("Java compilation FAILED:");
                for (CompileError err : errors) System.err.println("  Line " + err.line() + ": " + err.message());
                System.exit(1);
            }
            System.exit(0);
        } catch (Exception e) {
            System.err.println("Validation FAILED for: " + name);
            System.err.println();
            System.err.println("Error: " + e.getMessage());
            if (e.getCause() != null) System.err.println("Caused by: " + e.getCause().getMessage());
            System.exit(1);
        }
    }

    /**
     * Resolves a Java compiler, preferring the system compiler and falling back to ECJ.
     */
    private static @Nullable JavaCompiler resolveCompiler() {
        JavaCompiler system = ToolProvider.getSystemJavaCompiler();
        if (system != null) return system;
        for (JavaCompiler ecj : ServiceLoader.load(JavaCompiler.class)) return ecj;
        return null;
    }

    /**
     * Compiles a Lumen script source and returns the generated Java code.
     * Throws on parse or emit errors, making validation failures visible.
     *
     * @param source     the Lumen script source text
     * @param scriptName a display name for the script (used in error messages)
     * @return the generated Java source code
     */
    public @NotNull String compile(@NotNull String source, @NotNull String scriptName) {
        CodegenContext ctx = new CodegenContext(scriptName);
        TypeEnv env = new TypeEnv();
        JavaBuilder builder = new JavaBuilder();
        CodeEmitter.generate(source, patternRegistry, env, ctx, builder);
        return ClassBuilder.buildClass(scriptName, ctx, builder);
    }

    /**
     * Compiles the generated Java source code. Tries the system Java compiler first,
     * then falls back to the Eclipse Compiler for Java.
     *
     * @param javaSource the generated Java source code
     * @param className  the fully qualified class name
     * @return a list of compile errors (empty if successful)
     */
    public @NotNull List<CompileError> compileJava(@NotNull String javaSource, @NotNull String className) {
        List<CompileError> errors = new ArrayList<>();
        JavaCompiler compiler = resolveCompiler();
        if (compiler == null) {
            errors.add(new CompileError(-1, "No Java compiler available (neither javac nor ECJ found)"));
            return errors;
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Boolean ok = compiler.getTask(null, null, diagnostics, List.of("-classpath", System.getProperty("java.class.path"), "-proc:none"), null, List.of(new SourceFile(className, javaSource))).call();
        if (ok == null || !ok) {
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR)
                    errors.add(new CompileError(d.getLineNumber(), d.getMessage(null)));
            }
        }
        return errors;
    }

    /**
     * Returns the total count of all registered patterns across all categories.
     */
    public int patternCount() {
        return patternRegistry.getStatements().size() + patternRegistry.getBlocks().size() + patternRegistry.getExpressions().size() + patternRegistry.getConditionRegistry().getConditions().size() + patternRegistry.getLoopRegistry().getLoops().size();
    }

    /**
     * Returns the pattern registry containing all registered patterns.
     */
    public @NotNull PatternRegistry patternRegistry() {
        return patternRegistry;
    }

    /**
     * Returns the type registry containing all registered type bindings.
     */
    public @NotNull TypeRegistry typeRegistry() {
        return typeRegistry;
    }

    /**
     * @param line    the 1 based Java line number, or -1 if unknown
     * @param message the error message
     */
    public record CompileError(long line, @NotNull String message) {
    }
}
