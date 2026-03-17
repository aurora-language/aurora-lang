package aurora.parser.tree.expr;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;

/**
 * Represents a range expression (e.g., {@code 1..10} or {@code 1..=10}).
 * Often used in loops or pattern matching.
 */
public class RangeExpr extends Expr {
    /** The starting value of the range. */
    public final Expr start;

    /** The ending value of the range. */
    public final Expr end;

    /** Indicates whether the end value is included (..=) or excluded (..). */
    public final boolean inclusive;
    
    public RangeExpr(SourceLocation loc, Expr start, Expr end, boolean inclusive) {
        super(loc);
        this.start = start;
        this.end = end;
        this.inclusive = inclusive;
    }

    @Override
    public String toString() {
        return start + (inclusive ? "..=" : "..") + end;
    }
}
