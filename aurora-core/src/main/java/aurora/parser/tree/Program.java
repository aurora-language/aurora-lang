package aurora.parser.tree;

import aurora.parser.SourceLocation;
import aurora.parser.tree.stmt.ExprStmt;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a complete Aurora source file (compilation unit).
 * It contains an optional package declaration, a list of imports, and top-level statements.
 */
public class Program extends Node {
    /** The package declaration of this program. May be null. */
    public final Package aPackage;

    /** A list of modules imported by this program. */
    public final List<Import> imports;

    /** The top-level statements (functions, classes, etc.) in this program. */
    public final List<Statement> statements;

    /**
     * Initializes a new Program node.
     *
     * @param loc        The location of the entire file.
     * @param aPackage   The package declaration.
     * @param imports    The list of imports.
     * @param statements The top-level statements.
     */
    public Program(SourceLocation loc, Package aPackage, List<Import> imports, List<Statement> statements) {
        super(loc);
        this.aPackage = aPackage;
        this.imports = imports;
        this.statements = statements;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (aPackage != null) {
            sb.append(aPackage).append("\n");
        }
        if (imports != null && !imports.isEmpty()) {
            for (var _import : imports) {
                sb.append(_import).append("\n");
            }
        }
        if (statements != null) {
            for (var stmt : statements) {
                Node n = stmt;
                if (stmt instanceof ExprStmt expr) {
                    n = expr.expr;
                }
                sb.append(n).append("\n");
            }
        }
        return sb.toString();
    }

    public static class Package extends Node {
        public final String namespace;

        public Package(SourceLocation loc, String namespace) {
            super(loc);
            this.namespace = namespace;
        }

        @Override
        public String toString() {
            return "namespace " + namespace;
        }
    }

    public static class Import extends Node {
        public final String path;

        public Import(SourceLocation loc, String path) {
            super(loc);
            this.path = path;
        }
        
        @Override
        public String toString() {
            return "use " + path;
        }
    }

    public static final class ImportWildCard extends Import {
        public ImportWildCard(SourceLocation loc, String path) {
            super(loc, path);
        }
        
        @Override
        public String toString() {
            return "use " + path + ".*";
        }
    }

    public static final class ImportAlias extends Import {
        public final String alias;

        public ImportAlias(SourceLocation loc, String path, String alias) {
            super(loc, path);
            this.alias = alias;
        }

        @Override
        public String toString() {
            return "use " + path + " as " + alias;
        }
    }

    public static final class ImportMulti extends Import {
        public final List<Member> imports;

        public ImportMulti(SourceLocation loc, String path, List<Member> imports) {
            super(loc, path);
            this.imports = imports;
        }

        public record Member(String id, String alias) {
            public boolean hasAlias() {
                return alias != null;
            }

            @Override
            public @NotNull String toString() {
                return id + (alias != null ? " as " + alias : "");
            }
        }

        @Override
        public String toString() {
            return "use " + path + ".{" + imports.stream().map(Member::toString).collect(Collectors.joining(", ")) + "}";
        }
    }
}
