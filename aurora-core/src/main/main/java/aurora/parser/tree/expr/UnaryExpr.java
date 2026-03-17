package aurora.parser.tree.expr;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;
import aurora.parser.tree.util.UnaryOperator;

/**
 * Represents an expression with a single operand and a unary operator.
 */
public class UnaryExpr extends Expr {
    /** The expression being operated upon. */
    public final Expr operand;

    /** The unary operator being applied. */
    public final UnaryOperator op;

    public UnaryExpr(SourceLocation loc, Expr operand, UnaryOperator op) {
        super(loc);
        this.operand = operand;
        this.op = op;
    }

    @Override
    public String toString() {
        return (op == UnaryOperator.NONNULL ? op + " " : op.toString()) + operand;
    }
}
