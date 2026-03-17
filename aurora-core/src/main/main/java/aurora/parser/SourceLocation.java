package aurora.parser;

import org.jetbrains.annotations.NotNull;

/**
 * Represents a location in a source file.
 * This record stores the file name, line/column ranges, and byte offsets
 * to precisely locate a fragment of Aurora source code.
 *
 * @param sourceName  The name or path of the source file.
 * @param line        The starting line number (1-indexed).
 * @param column      The starting column number (1-indexed).
 * @param endLine     The ending line number (inclusive).
 * @param endColumn   The ending column number (inclusive).
 * @param startOffset The zero-based starting byte offset in the file.
 * @param endOffset   The zero-based ending byte offset in the file.
 */
public record SourceLocation(String sourceName, int line, int column, int endLine, int endColumn, int startOffset, int endOffset) {
    /**
     * Constructs a default SourceLocation representing an unknown position.
     */
    public SourceLocation() {
        this("<unknown>", 0, 0, 0, 0, 0, 0);
    }

    /**
     * Returns a string representation of the source location in the format "sourceName line:column-endLine:endColumn".
     *
     * @return A formatted string describing the location.
     */
    @Override
    public @NotNull String toString() {
        return String.format("%s %d:%d-%d:%d", sourceName, line, column, endLine, endColumn);
    }

    /**
     * Calculates the length of the source fragment in bytes.
     *
     * @return The difference between endOffset and startOffset.
     */
    public int getLength() {
        return endOffset - startOffset;
    }
}