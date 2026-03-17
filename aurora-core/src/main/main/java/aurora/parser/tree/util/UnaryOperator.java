package aurora.parser.tree.util;

/**
 * Enumeration of all unary (one-operand) operators in Aurora.
 */
public enum UnaryOperator {
    /** Logical negation. */
    NOT("!"),
    /** Numeric negation. */
    NEGATE("-"),
    /** Numeric identity (positive prefix). */
    POSITIVE("+"),
    /** Non-null assertion (suffix). */
    NONNULL("nonnull");

    private final String symbol;

    UnaryOperator(String symbol) {
        this.symbol = symbol;
    }

    @Override
    public String toString() {
        return symbol;
    }
}
