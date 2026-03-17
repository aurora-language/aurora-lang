package aurora.parser;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.List;

/**
 * An error listener for the Aurora compiler that formats and prints syntax errors to the console.
 * It provides rich, Rust-style error messages including line snippets, pointers (^^^),
 * and ANSI color highlighting for better developer experience in the terminal.
 */
public class CompilerErrorListener extends BaseErrorListener {
    /** The name of the source file being parsed. */
    private final String sourceName;

    /** The full source code string, used to extract context lines for error messages. */
    private final String sourceCode;

    /** The individual lines of the source code. */
    private final String[] lines;

    // ANSI color codes for terminal highlighting
    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String BOLD = "\u001B[1m";
    private static final String CYAN = "\u001B[36m";

    /** A list of formatted error messages collected during parsing. */
    private final List<String> errors = new ArrayList<>();

    /**
     * Constructs a new CompilerErrorListener.
     *
     * @param sourceName The name/path of the source file.
     * @param sourceCode The content of the source file.
     */
    public CompilerErrorListener(String sourceName, String sourceCode) {
        this.sourceName = sourceName;
        this.sourceCode = sourceCode;
        this.lines = sourceCode.split("\r?\n");
    }

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
            String msg, RecognitionException e) {
        StringBuilder sb = new StringBuilder();

        sb.append(RED).append(BOLD).append("error: ").append(RESET).append(BOLD).append(msg).append(RESET).append("\n");
        sb.append(CYAN).append("  --> ").append(RESET).append(sourceName).append(":").append(line).append(":")
                .append(charPositionInLine + 1).append("\n");

        String lineNumberStr = String.valueOf(line);
        String padding = " ".repeat(lineNumberStr.length());

        sb.append(CYAN).append(padding).append(" |").append(RESET).append("\n");

        if (line > 0 && line <= lines.length) {
            String sourceLine = lines[line - 1];
            sb.append(CYAN).append(lineNumberStr).append(" | ").append(RESET).append(sourceLine).append("\n");

            sb.append(CYAN).append(padding).append(" | ").append(RESET);

            // Calculate squiggly length based on token text if available
            int errorLength = 1;
            if (offendingSymbol instanceof Token token) {
                String text = token.getText();
                if (text != null && !text.equals("<EOF>")) {
                    errorLength = Math.max(1, text.length());
                    int nlIdx = text.indexOf('\n');
                    if (nlIdx != -1)
                        text = text.substring(0, nlIdx);
                    errorLength = Math.max(1, text.length());
                }
            }

            // Build the pointer/squiggly string mapping to characters
            StringBuilder squiggly = new StringBuilder();

            // Add spaces up to charPosition
            for (int i = 0; i < charPositionInLine; i++) {
                if (i < sourceLine.length() && sourceLine.charAt(i) == '\t') {
                    squiggly.append('\t'); // Preserve tabs so alignment is correct
                } else {
                    squiggly.append(' ');
                }
            }

            // Add the red pointer
            squiggly.append(RED).append(BOLD);
            for (int i = 0; i < errorLength; i++) {
                squiggly.append('^');
            }
            squiggly.append(RESET);

            sb.append(squiggly.toString()).append("\n");
        }

        errors.add(sb.toString());
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<String> getErrors() {
        return errors;
    }

    public String getFormattedErrors() {
        return String.join("\n", errors);
    }
}
