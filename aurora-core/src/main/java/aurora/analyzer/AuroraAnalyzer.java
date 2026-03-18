package aurora.analyzer;

import aurora.parser.AuroraParser;
import aurora.parser.SourceLocation;
import aurora.parser.tree.Program;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.util.ArrayList;
import java.util.List;

public final class AuroraAnalyzer {
    private static final String SOURCE_PARSER      = "Aurora Parser";
    private static final String SOURCE_TYPECHECKER = "Aurora TypeChecker";
    private static final String SOURCE_INTERNAL    = "Aurora Analyzer";

    public AnalysisResult analyze(String source, String sourceName) {
        return analyze(source, sourceName, null);
    }

    public AnalysisResult analyze(String source, String sourceName, ModuleResolver modules) {
        List<AuroraDiagnostic> diagnostics = new ArrayList<>();
        DiagnosticCollector collector = new DiagnosticCollector(diagnostics, sourceName);

        Program program;

        try {
            program = AuroraParser.parseWithListener(source, sourceName, collector);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            diagnostics.add(AuroraDiagnostic.error(null, "Internal parse error: " + msg, SOURCE_INTERNAL));
            return new AnalysisResult(null, diagnostics);
        }

        if (program != null) {
            // Phase 1+2: type inference and ReturnStmt rewrite.
            // ASTPostProcessor runs TypeInferenceEngine internally; diagnostics it
            // produces (e.g. "both operands have unknown types") are retrieved via
            // ASTPostProcessor.getLastDiagnostics() immediately after the call.
            try {
                ASTPostProcessor.process(program, modules);
                // Collect diagnostics emitted by TypeInferenceEngine during inference.
                diagnostics.addAll(ASTPostProcessor.getLastDiagnostics());
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                diagnostics.add(AuroraDiagnostic.error(null, "Post-processing error: " + msg, SOURCE_INTERNAL));
            }

            // Phase 3: full type checking pass.
            try {
                TypeChecker typeChecker = new TypeChecker(program, modules);
                typeChecker.visitProgram(program);
                diagnostics.addAll(typeChecker.getDiagnostics());
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                diagnostics.add(AuroraDiagnostic.error(null, "Type checking error: " + msg, SOURCE_INTERNAL));
            }
        }

        return new AnalysisResult(program, diagnostics);
    }

    private static final class DiagnosticCollector extends BaseErrorListener {
        private final List<AuroraDiagnostic> diagnostics;
        private final String sourceName;

        DiagnosticCollector(List<AuroraDiagnostic> diagnostics, String sourceName) {
            this.diagnostics = diagnostics;
            this.sourceName  = sourceName;
        }

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer,
                                Object offendingSymbol,
                                int line,
                                int charPositionInLine,
                                String msg,
                                RecognitionException e) {
            int endCol = charPositionInLine + 1;
            if (offendingSymbol instanceof org.antlr.v4.runtime.Token token) {
                String text = token.getText();
                if (text != null && !text.equals("<EOF>")) {
                    int nlIdx = text.indexOf('\n');
                    String firstLine = nlIdx != -1 ? text.substring(0, nlIdx) : text;
                    endCol = charPositionInLine + firstLine.length();
                }
            }

            SourceLocation loc = new SourceLocation(
                    sourceName,
                    line, charPositionInLine,
                    line, Math.max(endCol, charPositionInLine + 1),
                    -1, -1);

            diagnostics.add(AuroraDiagnostic.error(loc, cleanMessage(msg), SOURCE_PARSER));
        }

        private static String cleanMessage(String msg) {
            if (msg == null) return "Syntax error";
            int expectingIdx = msg.indexOf(" expecting {");
            if (expectingIdx != -1) msg = msg.substring(0, expectingIdx) + " (unexpected token)";
            msg = msg.replace("extraneous input",                "Unexpected token");
            msg = msg.replace("mismatched input",                "Mismatched token");
            msg = msg.replace("no viable alternative at input",  "Invalid syntax at");
            msg = msg.replace("missing",                         "Missing");
            return msg;
        }
    }
}