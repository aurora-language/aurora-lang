package aurora.parser.tree.util;

/**
 * Enumeration of all binary (two-operand) operators supported by the Aurora language.
 */
public enum BinaryOperator {
    /** Addition operator. */
    ADD("+"),
    /** Subtraction operator. */
    SUB("-"),
    /** Multiplication operator. */
    MUL("*"),
    /** Division operator. */
    DIV("/"),
    /** Remainder (modulo) operator. */
    MOD("%"),
    /** Standard assignment. */
    ASSIGN("="),
    /** Addition assignment. */
    PLUS_ASSIGN("+="),
    /** Subtraction assignment. */
    MINUS_ASSIGN("-="),
    /** Multiplication assignment. */
    STAR_ASSIGN("*="),
    /** Division assignment. */
    SLASH_ASSIGN("/="),
    /** Remainder assignment. */
    PERCENT_ASSIGN("%="),
    /** Equality comparison. */
    EQ("=="),
    /** Inequality comparison. */
    NEQ("!="),
    /** Less than comparison. */
    LT("<"),
    /** Greater than comparison. */
    GT(">"),
    /** Less than or equal to comparison. */
    LE("<="),
    /** Greater than or equal to comparison. */
    GE(">="),
    /** Logical AND. */
    AND("&&"),
    /** Logical OR. */
    OR("||"),
    /** Exclusive range (start .. end). */
    RANGE_EXCL(".."),
    /** Inclusive range (start ..= end). */
    RANGE_INCL("..="),
    /** Containment check. */
    IN("in"),
    /** Null-coalescing (Elvis) operator. */
    ELVIS("?:");

    final String symbol;

    BinaryOperator(String symbol) {
        this.symbol = symbol;
    }

    @Override
    public String toString() {
        return symbol;
    }
}