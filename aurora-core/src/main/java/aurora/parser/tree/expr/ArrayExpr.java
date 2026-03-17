package aurora.parser.tree.expr;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents an array literal expression (e.g., {@code [1, 2, 3]}).
 */
public class ArrayExpr extends Expr {
    /** The list of expressions that form the elements of the array. */
    public final List<Expr> elements;

    public ArrayExpr(SourceLocation loc, List<Expr> elements) {
        super(loc);
        this.elements = elements;
    }

    @Override
    public String toString() {
        return "[" + elements.stream().map(Object::toString).collect(Collectors.joining(", ")) + "]";
    }
}
