package aurora.analyzer;

import aurora.parser.SourceLocation;

public record AuroraDiagnostic(SourceLocation location, String message, AuroraDiagnostic.Severity severity, String source) {
    public enum Severity {
        ERROR, WARNING, INFORMATION, HINT
    }

    public static AuroraDiagnostic error(SourceLocation loc, String message, String source) {
        return new AuroraDiagnostic(loc, message, Severity.ERROR, source);
    }

    public static AuroraDiagnostic warning(SourceLocation loc, String message, String source) {
        return new AuroraDiagnostic(loc, message, Severity.WARNING, source);
    }

    @Override
    public String toString() {
        return "[" + severity + "] " + source + ": " + message
                + (location != null ? " @ " + location : "");
    }
}