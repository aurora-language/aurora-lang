package aurora.parser.tree.expr;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;

/**
 * Represents the {@code self} keyword, which references the current instance of a class.
 */
public class SelfExpr extends Expr {
    public SelfExpr(SourceLocation loc) {
        super(loc);
    }

    @Override
    public String toString() {
        return "self";
    }
}
