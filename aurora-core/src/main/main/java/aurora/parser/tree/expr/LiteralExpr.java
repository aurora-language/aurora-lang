package aurora.parser.tree.expr;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;

/**
 * Represents a constant value literal (e.g., number, string, boolean, or null).
 */
public class LiteralExpr extends Expr {
    /** The actual value of the literal (e.g., an Integer, String, or Boolean). */
    public final Object value;

    /** The type category of the literal. */
    public final LiteralType type;

    public LiteralExpr(SourceLocation loc, Object value, LiteralType type) {
        super(loc);
        this.value = value;
        this.type = type;
    }

    public enum LiteralType {
        INT, LONG, FLOAT, DOUBLE, STRING, BOOL, NULL
    }

    @Override
    public String toString() {
        if (type == LiteralType.STRING) {
            return "\"" + value + "\"";
        }
        return String.valueOf(value);
    }
}
