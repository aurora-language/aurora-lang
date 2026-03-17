package aurora.parser;

/**
 * Thrown by the {@link AuroraParser} when a syntax error is encountered in the source code.
 * This exception encapsulates the error message and is caught by the CLI to provide user feedback.
 */
public class SyntaxErrorException extends RuntimeException {
    /**
     * Constructs a new SyntaxErrorException with the specified detail message.
     *
     * @param message The detailed error message explaining why the parsing failed.
     */
    public SyntaxErrorException(String message) {
        super(message);
    }
}
