package aurora.parser.tree.expr;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;

/**
 * Represents a null-coalescing (Elvis) expression (e.g., {@code a ?: b}).
 * If the left operand evaluates to a non-null value, it is returned;
 * otherwise, the right operand (the default value) is returned.
 */
public class ElvisExpr extends Expr {
    /** The potentially null expression on the left. */
    public final Expr left;

    /** The default expression to use if the left side is null. */
    public final Expr right;
    
    public ElvisExpr(SourceLocation loc, Expr left, Expr right) {
        super(loc);
        this.left = left;
        this.right = right;
    }

    @Override
    public String toString() {
        return left + " ?: " + right;
    }
}
