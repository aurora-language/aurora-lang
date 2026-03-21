package aurora.analyzer;

import aurora.Lib;
import aurora.parser.AuroraParser;
import aurora.parser.tree.Declaration;
import aurora.parser.tree.Program;
import aurora.parser.tree.Statement;
import aurora.parser.tree.decls.ClassDecl;
import aurora.parser.tree.decls.FunctionDecl;
import aurora.parser.tree.decls.InterfaceDecl;
import aurora.parser.tree.decls.RecordDecl;
import org.antlr.v4.runtime.BaseErrorListener;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Resolves external module imports for the Aurora LSP.
 *
 * <p>
 * Import mapping rules:
 * </p>
 * <ul>
 * <li>{@code use Aurora.Io} → {@code aurora/lib/Aurora/Io.ar} relative to
 * project root</li>
 * <li>{@code use some.module.Name} → {@code some/module/Name.ar} relative to
 * project root</li>
 * </ul>
 *
 * <p>
 * Parsed ASTs are cached; the cache is invalidated when a module file is edited
 * via the
 * {@link #invalidate(String)} method.
 * </p>
 */
public class ModuleResolver {
    /** Cache mapping import paths (e.g., "Aurora.Io") to their parsed {@link Program} ASTs. */
    private final Map<String, Optional<Program>> moduleCache = new ConcurrentHashMap<>();

    /** The project root directory, used to resolve relative imports. */
    volatile Path projectRoot;

    public void setProjectRoot(Path root) {
        this.projectRoot = root;
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    /** Invalidate a cached module when its source file changes. */
    public void invalidate(String importPath) {
        moduleCache.remove(importPath);
    }

    /**
     * Try to resolve the class/function/record with the given unqualified
     * {@code typeName}
     * from any module imported into {@code program}.
     */
    public Declaration resolveFromImports(Program program, String typeName) {
        if (program.imports == null || projectRoot == null)
            return null;

        for (Program.Import imp : program.imports) {
            // For wildcard/multi imports fall back to full path scan below
            Program mod = loadModule(imp.path);
            if (mod == null)
                continue;

            if (mod.statements == null)
                continue;
            for (Statement stmt : mod.statements) {
                if (stmt instanceof Declaration decl && typeName.equals(decl.name)) {
                    return decl;
                }
            }
        }
        return null;
    }

    /**
     * Resolve a qualified name like "Io" where the import is "Aurora.Io" and the
     * last
     * segment matches. Returns the top-level public declaration in that module file
     * whose name equals the last segment of the import path.
     */
    public Declaration resolveImportedModule(Program program, String name) {
        if (program.imports == null || projectRoot == null)
            return null;

        for (Program.Import imp : program.imports) {
            String lastSeg = lastSegment(imp.path);
            if (!lastSeg.equals(name))
                continue;

            Program mod = loadModule(imp.path);
            if (mod == null)
                continue;
            if (mod.statements == null)
                continue;

            // Return the first public class/record/trait/function that matches the
            // module name
            for (Statement stmt : mod.statements) {
                if (stmt instanceof ClassDecl cd && cd.name.equals(name))
                    return cd;
                if (stmt instanceof RecordDecl rd && rd.name.equals(name))
                    return rd;
                if (stmt instanceof InterfaceDecl id && id.name.equals(name))
                    return id;
                if (stmt instanceof FunctionDecl fd && fd.name.equals(name))
                    return fd;
            }
        }
        return null;
    }

    public Map<String, Declaration> loadImplicitImports() {
        Map<String, Declaration> result = new HashMap<>();
        if (projectRoot == null) return result;

        // Collect all paths: Aurora/Runtime scan + explicit Lib.implicitImports
        Set<String> paths = new LinkedHashSet<>();

        // 1. Scan Aurora/Runtime as before
        Path runtimeRoot = projectRoot.resolve("aurora/lib/Aurora/Runtime");
        if (Files.exists(runtimeRoot)) {
            try (Stream<Path> fs = Files.walk(runtimeRoot)) {
                fs.filter(p -> p.toString().endsWith(".ar"))
                        .forEach(file -> {
                            Path rel = projectRoot.resolve("aurora/lib").relativize(file);
                            String fqn = rel.toString()
                                    .replace(File.separatorChar, '.')
                                    .replaceAll("\\.ar$", "");
                            paths.add(fqn);
                        });
            } catch (IOException ignored) {}
        }

        // 2. Add explicit implicit imports from Lib
        paths.addAll(aurora.Lib.implicitImports);

        // Load all collected paths
        for (String fqn : paths) {
            Program mod = loadModule(fqn);
            if (mod == null || mod.statements == null) continue;
            for (Statement stmt : mod.statements) {
                if (stmt instanceof Declaration d && d.name != null) {
                    result.putIfAbsent(fqn, d);
                    result.putIfAbsent(d.name, d);
                }
            }
        }

        return result;
    }

    /**
     * Load and cache a module by its import path string (e.g. "Aurora.Io").
     * Returns null if the file doesn't exist or fails to parse.
     */
    public Program loadModule(String importPath) {
        if (moduleCache.containsKey(importPath)) {
            return moduleCache.get(importPath).orElse(null);
        }

        // Sentinel: mark as "in progress" to break recursive load cycles
        moduleCache.put(importPath, Optional.empty());

        Path file = resolveFile(importPath);
        if (file == null || !Files.exists(file)) {
            System.getLogger("aurora.analyzer").log(System.Logger.Level.DEBUG,
                    () -> String.format("  ModuleResolver: cannot find file for import '%s'", importPath));
            return null;
        }

        try {
            String src = Files.readString(file);
            System.getLogger("aurora.analyzer").log(System.Logger.Level.DEBUG,
                    () -> String.format("  ModuleResolver: loading '%s' from %s", importPath, file));
            BaseErrorListener errs = new BaseErrorListener();
            Program program = AuroraParser.parseWithListener(src, file.toUri().toString(), errs, this);
            moduleCache.put(importPath, Optional.of(program));
            return program;
        } catch (Exception e) {
            System.getLogger("aurora.analyzer").log(System.Logger.Level.WARNING,
                    "  ModuleResolver: failed to load " + importPath, e);
            return null;
        }
    }

    /**
     * Converts an import path like "Aurora.Io" to the file path
     * {@code <projectRoot>/aurora/lib/Aurora/Io.ar}.
     *
     * <p>
     * Heuristic: if the first segment starts with an uppercase letter, prepend
     * {@code aurora/lib/} (standard library). Otherwise treat as relative source
     * path from the project root.
     * </p>
     */
    private Path resolveFile(String importPath) {
        if (projectRoot == null)
            return null;

        String[] parts = importPath.split("\\.");
        // Standard lib heuristic: first segment uppercase → aurora/lib/<segments>.ar
        String relative = String.join("/", parts) + ".ar";
        Path stdLib = projectRoot.resolve("aurora/lib/" + relative);
        if (Files.exists(stdLib))
            return stdLib;

        // Try project-relative path
        Path projectRel = projectRoot.resolve(relative);
        if (Files.exists(projectRel))
            return projectRel;

        // Try src/ subfolder
        Path srcRel = projectRoot.resolve("src/" + relative);
        if (Files.exists(srcRel))
            return srcRel;

        return null;
    }

    private static String lastSegment(String dotPath) {
        int idx = dotPath.lastIndexOf('.');
        return idx >= 0 ? dotPath.substring(idx + 1) : dotPath;
    }
}