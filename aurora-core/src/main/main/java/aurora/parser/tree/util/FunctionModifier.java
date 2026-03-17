package aurora.parser.tree.util;

/**
 * Modifiers applicable to functions.
 */
public enum FunctionModifier {
    ASYNC("async"),
    OVERRIDE("override"),
    STATIC("static"),
    ABSTRACT("abstract"),
    NATIVE("native");

    final String symbol;

    FunctionModifier(String symbol) {
        this.symbol = symbol;
    }

    @Override
    public String toString() {
        return symbol;
    }
}
