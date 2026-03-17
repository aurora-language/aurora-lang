package aurora.parser.tree.expr;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;

/**
 * Represents the {@code super} keyword, used to access members or constructors
 * of the parent class.
 */
public class SuperExpr extends Expr {
    public SuperExpr(SourceLocation loc) {
        super(loc);
    }

    @Override
    public String toString() {
        return "super";
    }
}
