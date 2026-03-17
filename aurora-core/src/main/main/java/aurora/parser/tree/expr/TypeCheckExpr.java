package aurora.parser.tree.expr;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;
import aurora.parser.tree.TypeNode;

/**
 * Represents a type check expression (e.g., {@code expr is Type}).
 */
public class TypeCheckExpr extends Expr {
    /** The type to check against. */
    public final TypeNode type;

    /** The expression being checked. */
    public final Expr check;

    public TypeCheckExpr(SourceLocation loc, TypeNode type, Expr check) {
        super(loc);
        this.type = type;
        this.check = check;
    }

    @Override
    public String toString() {
        return check + " is " + type;
    }
}
