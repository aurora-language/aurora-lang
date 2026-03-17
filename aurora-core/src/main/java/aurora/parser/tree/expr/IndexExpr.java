package aurora.parser.tree.expr;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;

/**
 * Represents an index access expression (e.g., {@code array[index]}).
 */
public class IndexExpr extends Expr {
    /** The object being indexed. */
    public final Expr object;

    /** The index expression. */
    public final Expr index;

    public IndexExpr(SourceLocation loc, Expr object, Expr index) {
        super(loc);
        this.object = object;
        this.index = index;
    }

    @Override
    public String toString() {
        return object + "[" + index + "]";
    }
}
