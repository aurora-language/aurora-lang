package aurora.parser.tree.expr;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;

/**
 * Represents an {@code await} expression used to pause execution until a future or task completes.
 */
public class AwaitExpr extends Expr {
    /** The asynchronous expression being awaited. */
    public final Expr expr;

    public AwaitExpr(SourceLocation loc, Expr expr) {
        super(loc);
        this.expr = expr;
    }

    @Override
    public String toString() {
        return "await " + expr;
    }
}
