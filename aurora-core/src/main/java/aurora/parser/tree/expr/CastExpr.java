package aurora.parser.tree.expr;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;
import aurora.parser.tree.TypeNode;

/**
 * Represents a type casting expression (e.g., {@code expr as Type}).
 */
public class CastExpr extends Expr {
    /** The expression being cast. */
    public final Expr expr;

    /** The target type of the cast. */
    public final TypeNode type;

    /** Indicates whether the cast is "safe" (e.g., returns null instead of throwing on failure). */
    public final boolean isSafe;

    public CastExpr(SourceLocation loc, Expr expr, TypeNode type, boolean isSafe) {
        super(loc);
        this.expr = expr;
        this.type = type;
        this.isSafe = isSafe;
    }

    @Override
    public String toString() {
        return expr + " as " + (isSafe ? "?" : "") + type;
    }
}
