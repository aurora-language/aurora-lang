package aurora.parser.tree.stmt;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;
import aurora.parser.tree.Statement;

/**
 * Represents an expression used as a standalone statement.
 * This is common for function calls, assignments, or increments.
 */
public class ExprStmt extends Statement {
    /** The underlying expression. */
    public final Expr expr;

    public ExprStmt(SourceLocation loc, Expr expr) {
        super(loc);
        this.expr = expr;
    }

    @Override
    public String toString() {
        return expr.toString();
    }
}
