package aurora.analyzer;

import aurora.parser.tree.Program;

import java.util.List;

public record AnalysisResult(Program program, List<AuroraDiagnostic> diagnostics) {

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == AuroraDiagnostic.Severity.ERROR);
    }
}