package aurora.parser.tree.expr;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;
import aurora.parser.tree.util.BinaryOperator;

/**
 * Represents an expression with two operands and a binary operator.
 */
public class BinaryExpr extends Expr {
    /** The expression on the left side of the operator. */
    public final Expr left;

    /** The expression on the right side of the operator. */
    public final Expr right;

    /** The binary operator being applied. */
    public final BinaryOperator op;

    public BinaryExpr(SourceLocation loc, Expr left, Expr right, BinaryOperator op) {
        super(loc);
        this.left = left;
        this.right = right;
        this.op = op;
    }

    @Override
    public String toString() {
        return left + " " + op + " " + right;
    }
}
