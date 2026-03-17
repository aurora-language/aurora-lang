package aurora.parser.tree.expr;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a function or method call expression.
 */
public class CallExpr extends Expr {
    /** The expression being called (the callee). */
    public final Expr callee;

    /** The list of arguments passed to the call. */
    public final List<Argument> arguments;

    public CallExpr(SourceLocation loc, Expr callee, List<Argument> arguments) {
        super(loc);
        this.callee = callee;
        this.arguments = arguments != null ? arguments : Collections.emptyList();
    }

    public static class Argument {
        public final String name; // Check for named arguments
        public final boolean isSpread; // Check for spread arguments
        public final Expr value;

        public Argument(String name, boolean isSpread, Expr value) {
            this.name = name;
            this.isSpread = isSpread;
            this.value = value;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (isSpread) sb.append("*");
            if (name != null) sb.append(name).append(": ");
            sb.append(value);
            return sb.toString();
        }
    }

    @Override
    public String toString() {
        return callee + "(" + arguments.stream().map(Argument::toString).collect(Collectors.joining(", ")) + ")";
    }
}
