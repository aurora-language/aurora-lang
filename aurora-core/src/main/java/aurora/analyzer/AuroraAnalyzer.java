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

        Program program = null;

        // ── パース ──────────────────────────────────────────────
        try {
            program = AuroraParser.parseWithListener(source, sourceName, collector);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            diagnostics.add(AuroraDiagnostic.error(null, "Internal parse error: " + msg, SOURCE_INTERNAL));
            return new AnalysisResult(null, diagnostics);
        }

        // ── 型チェック ──────────────────────────────────────────
        if (program != null) {
            try {
                TypeChecker typeChecker = new TypeChecker(program, modules);
                typeChecker.visitProgram(program);
                diagnostics.addAll(typeChecker.getDiagnostics());
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                diagnostics.add(AuroraDiagnostic.error(null, "Internal type-check error: " + msg, SOURCE_INTERNAL));
            }
        }

        return new AnalysisResult(program, diagnostics);
    }

    // ── ANTLR エラーを AuroraDiagnostic に集める内部クラス ───────────

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
            // ANTLR が空白文字や制御文字を認識できなかった場合のノイズを除去する。
            // 例: ブロックコメント内の改行、Unicode スペースなど。
            if (isWhitespaceLexerError(msg)) return;

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

        /**
         * "token recognition error at: 'X'" で X が空白・制御文字のみの場合は
         * ユーザーに見せる価値がないので除外する。
         */
        private static boolean isWhitespaceLexerError(String msg) {
            if (msg == null) return false;
            // ANTLR のフォーマット: "token recognition error at: 'X'"
            int atIdx = msg.indexOf("token recognition error at: '");
            if (atIdx < 0) return false;
            int from = atIdx + "token recognition error at: '".length();
            int to   = msg.lastIndexOf('\'');
            if (to <= from) return false;
            String chars = msg.substring(from, to);
            return chars.chars().allMatch(c -> c <= 0x20 || Character.isWhitespace(c));
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