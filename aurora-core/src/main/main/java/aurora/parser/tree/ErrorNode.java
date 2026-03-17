package aurora.parser.tree;

import aurora.parser.SourceLocation;

/**
 * Represents a syntax error encountered during parsing.
 * This node allows the parser to recover and continue while preserving enough
 * information for tools like the LSP to display the error location and message.
 */
public class ErrorNode extends Statement {
    /** A descriptive error message explaining what went wrong. */
    public final String errorMessage;

    /** The underlying exception that caused the error, if available. */
    public final Throwable cause;

    public ErrorNode(SourceLocation loc) {
        super(loc);
        this.errorMessage = "Parse error";
        this.cause = null;
    }

    public ErrorNode(SourceLocation loc, String errorMessage) {
        super(loc);
        this.errorMessage = errorMessage;
        this.cause = null;
    }

    public ErrorNode(SourceLocation loc, String errorMessage, Throwable cause) {
        super(loc);
        this.errorMessage = errorMessage;
        this.cause = cause;
    }

    @Override
    public String toString() {
        return "ErrorNode: " + errorMessage;
    }
}
