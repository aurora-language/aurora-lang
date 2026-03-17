package aurora.parser.tree.stmt;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;
import aurora.parser.tree.Node;
import aurora.parser.tree.Statement;
import aurora.parser.tree.TypeNode;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a pattern matching statement.
 * Match statements compare an expression against various patterns and execute
 * the code block associated with the first matching pattern.
 */
public class MatchStmt extends Statement {
    /** The expression being evaluated for matching. */
    public final Expr expression;

    /** The list of patterns and their associated blocks. */
    public final List<MatchCase> cases;

    public MatchStmt(SourceLocation loc, Expr expression, List<MatchCase> cases) {
        super(loc);
        this.expression = expression;
        this.cases = cases;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("match ").append(expression).append(" {\n");
        for (MatchCase c : cases) {
            String caseStr = c.toString();
            String indentedCases = caseStr.lines()
                    .map(line -> " ".repeat(4) + line)
                    .collect(Collectors.joining("\n"));

            sb.append(indentedCases).append("\n");
        }
        sb.append("}");
        return sb.toString();
    }

    public static class MatchCase extends Node {
        public final Pattern pattern;
        public final Expr guard;
        public final Node body;

        public MatchCase(SourceLocation loc, Pattern pattern, Expr guard, Node body) {
            super(loc);
            this.pattern = pattern;
            this.guard = guard;
            this.body = body;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(pattern);
            if (guard != null) {
                sb.append(" if ").append(guard);
            }
            sb.append(" => ").append(body);
            return sb.toString();
        }
    }

    public static abstract class Pattern extends Node {
        public Pattern(SourceLocation loc) {
            super(loc);
        }
    }

    public static class LiteralPattern extends Pattern {
        public final Expr literal;

        public LiteralPattern(SourceLocation loc, Expr literal) {
            super(loc);
            this.literal = literal;
        }

        @Override
        public String toString() {
            return literal.toString();
        }
    }

    public static class MultiPattern extends Pattern {
        public final List<Pattern> patterns;

        public MultiPattern(SourceLocation loc, List<Pattern> patterns) {
            super(loc);
            this.patterns = patterns;
        }

        @Override
        public String toString() {
            return patterns.stream().map(Object::toString).collect(Collectors.joining(", "));
        }
    }

    public static class RangePattern extends Pattern {
        public final Expr start;
        public final Expr end;
        public final boolean inclusive;

        public RangePattern(SourceLocation loc, Expr start, Expr end, boolean inclusive) {
            super(loc);
            this.start = start;
            this.end = end;
            this.inclusive = inclusive;
        }

        @Override
        public String toString() {
            return start.toString() + (inclusive ? "..=" : "..") + end.toString();
        }
    }

    public static class IsPattern extends Pattern {
        public final TypeNode type;

        public IsPattern(SourceLocation loc, TypeNode type) {
            super(loc);
            this.type = type;
        }

        @Override
        public String toString() {
            return "is " + type;
        }
    }

    public static class IdentifierPattern extends Pattern {
        public final String name;

        public IdentifierPattern(SourceLocation loc, String name) {
            super(loc);
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static class DestructurePattern extends Pattern {
        public final List<String> names;

        public DestructurePattern(SourceLocation loc, List<String> names) {
            super(loc);
            this.names = names;
        }

        @Override
        public String toString() {
            return "(" + String.join(", ", names) + ")";
        }
    }

    public static class DefaultPattern extends Pattern {
        public DefaultPattern(SourceLocation loc) {
            super(loc);
        }

        @Override
        public String toString() {
            return "default";
        }
    }
}