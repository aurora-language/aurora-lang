package aurora.parser.tree.util;

/**
 * Defines the access levels for declarations (classes, functions, fields, etc.) in Aurora.
 */
public enum Visibility {
    /** Visible from any module. */
    PUBLIC("pub"),
    /** Visible only within the same file or containing class. */
    PRIVATE("local"),
    /** Visible within the same class and its subclasses. */
    PROTECTED("protected");

    final String symbol;

    Visibility(String symbol) {
        this.symbol = symbol;
    }

    @Override
    public String toString() {
        return symbol;
    }
}
