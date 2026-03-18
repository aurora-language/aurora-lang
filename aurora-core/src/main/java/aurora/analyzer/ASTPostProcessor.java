package aurora.analyzer;

import aurora.parser.tree.*;
import aurora.parser.tree.decls.*;
import aurora.parser.tree.expr.*;
import aurora.parser.tree.stmt.*;

import java.util.Collections;
import java.util.List;

/**
 * Post-parse AST processor that runs after the ANTLR-based {@link aurora.parser.AuroraParser}
 * has produced a raw AST.
 *
 * <h2>Why this exists</h2>
 * The parser cannot perform type inference during the parse pass because the full AST is not
 * yet available—forward references and mutually recursive functions are not visible until
 * parsing is complete. This class encapsulates the two-phase post-processing that was
 * previously spread between {@link aurora.parser.AuroraParser#visitBlock} and
 * {@link TypeInferenceEngine}:
 *
 * <ol>
 *   <li><b>Phase 1 – Type Inference:</b> Run {@link TypeInferenceEngine#infer()} over the
 *       complete AST so that every {@link BlockStmt#returnType} is annotated with the
 *       correct {@link TypeNode}.</li>
 *   <li><b>Phase 2 – ReturnStmt Rewrite:</b> Walk every {@link BlockStmt} whose inferred
 *       {@code returnType} is <em>non-void</em> and rewrite the trailing
 *       {@link ExprStmt} into a {@link ControlStmt.ReturnStmt}.
 *       Loop bodies are always void and are therefore skipped for the rewrite
 *       (but are still descended into so that inner functions are processed).</li>
 * </ol>
 *
 * <p><b>Diagnostics:</b>
 * {@link TypeInferenceEngine} accumulates diagnostics (e.g. "both operands have unknown
 * types") during Phase 1. These are exposed via {@link #getLastDiagnostics()} so that
 * callers such as {@link AuroraAnalyzer} can forward them to the user.
 *
 * <p><b>Note on {@link TypeInferenceEngine#isVoid}:</b>
 * This method only checks whether the supplied {@link TypeNode} is itself void/null — it does
 * <em>not</em> perform any inference. Inference happens exclusively in Phase 1 above.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Program program = AuroraParser.parse(source, sourceName);
 * // AuroraParser.parse() already calls ASTPostProcessor.process(program) internally,
 * // so manual invocation is only needed when constructing a Program outside the parser.
 * }</pre>
 */
public final class ASTPostProcessor {

    private ASTPostProcessor() {}

    /**
     * Diagnostics produced by the most recent {@link #process} call on this class.
     * Stored as a thread-local to keep the static API while still making diagnostics
     * retrievable after the call returns.
     */
    private static final ThreadLocal<List<AuroraDiagnostic>> lastDiagnostics =
            ThreadLocal.withInitial(Collections::emptyList);

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Runs both post-processing phases on {@code program}.
     *
     * @param program the freshly parsed AST root
     */
    public static void process(Program program) {
        process(program, null);
    }

    /**
     * Runs both post-processing phases on {@code program} using the supplied
     * {@link ModuleResolver} for cross-module type lookups.
     *
     * @param program the freshly parsed AST root
     * @param modules module resolver (may be {@code null})
     */
    public static void process(Program program, ModuleResolver modules) {
        if (program == null) return;

        // Phase 1: annotate every BlockStmt.returnType via full-AST type inference
        TypeInferenceEngine engine = new TypeInferenceEngine(program, modules);
        engine.infer();

        // Preserve diagnostics from inference so callers can retrieve them.
        lastDiagnostics.set(engine.getDiagnostics());

        // Phase 2: rewrite trailing ExprStmt -> ReturnStmt for non-void blocks
        rewriteProgram(program);
    }

    /**
     * Returns the diagnostics produced by the most recent {@link #process} call
     * on the current thread.
     *
     * <p>Intended for use by {@link AuroraAnalyzer}:
     * <pre>{@code
     * ASTPostProcessor.process(program, modules);
     * diagnostics.addAll(ASTPostProcessor.getLastDiagnostics());
     * }</pre>
     *
     * @return an unmodifiable list of {@link AuroraDiagnostic}; never {@code null}
     */
    public static List<AuroraDiagnostic> getLastDiagnostics() {
        return lastDiagnostics.get();
    }

    // -----------------------------------------------------------------------
    // Phase 2 - ReturnStmt rewrite walk
    // -----------------------------------------------------------------------

    private static void rewriteProgram(Program program) {
        if (program.statements == null) return;
        for (Statement s : program.statements) {
            rewriteStatement(s);
        }
    }

    private static void rewriteStatement(Statement stmt) {
        switch (stmt) {
            case FunctionDecl     d -> rewriteFunctionDecl(d);
            case ThreadDecl       d -> rewriteBlock(d.body);
            case ClassDecl        d -> rewriteClassDecl(d);
            case RecordDecl       d -> rewriteRecordDecl(d);
            case BlockStmt        b -> rewriteBlock(b);
            case IfStmt           s -> rewriteIfStmt(s);
            case LoopStmt         s -> rewriteLoopStmt(s);
            case TryStmt          s -> rewriteTryStmt(s);
            case MatchStmt        s -> rewriteMatchStmt(s);
            case InitializerBlock s -> rewriteBlock(s.body);
            case ExprStmt         s -> rewriteExpr(s.expr);
            default                 -> { /* leaf - nothing to rewrite */ }
        }
    }

    // ----- Declaration rewrites -----

    private static void rewriteFunctionDecl(FunctionDecl d) {
        // Expression-body functions (fun foo() = expr) already have a ReturnStmt
        // injected by the parser at parse time; only block-body functions need rewriting.
        if (d.body != null && !d.isExpressionBody) {
            rewriteBlock(d.body);
        }
    }

    private static void rewriteClassDecl(ClassDecl d) {
        if (d.members == null) return;
        for (Declaration m : d.members) {
            switch (m) {
                case FunctionDecl    f -> rewriteFunctionDecl(f);
                case ConstructorDecl c -> rewriteBlock(c.body);
                // InitializerBlock is a Statement - it appears inside classMember but is
                // treated as a Statement in the AST.
                default -> { /* no-op */ }
            }
        }
    }

    private static void rewriteRecordDecl(RecordDecl d) {
        if (d.members == null) return;
        for (Declaration m : d.members) {
            if (m instanceof FunctionDecl f) rewriteFunctionDecl(f);
        }
    }

    // ----- Block rewrite -----

    private static void rewriteBlock(BlockStmt block) {
        if (block == null || block.statements == null || block.statements.isEmpty()) return;

        // Recurse into inner statements first.
        for (Statement s : block.statements) {
            rewriteStatement(s);
        }

        // If this block should return a value, convert the trailing ExprStmt to ReturnStmt.
        if (!TypeInferenceEngine.isVoid(block.returnType)) {
            int last = block.statements.size() - 1;
            if (block.statements.get(last) instanceof ExprStmt exprStmt) {
                block.statements.set(last,
                        new ControlStmt.ReturnStmt(exprStmt.loc, exprStmt.expr));
            }
        }
    }

    // ----- Control-flow rewrites -----

    private static void rewriteIfStmt(IfStmt s) {
        rewriteBlock(s.thenBlock);
        if (s.elseIfs != null) {
            for (IfStmt.ElseIf ei : s.elseIfs) rewriteBlock(ei.block);
        }
        if (s.elseBlock != null) rewriteBlock(s.elseBlock);
    }

    private static void rewriteLoopStmt(LoopStmt s) {
        switch (s) {
            case LoopStmt.ForStmt       f -> rewriteBlock(f.body);
            case LoopStmt.WhileStmt     w -> rewriteBlock(w.body);
            case LoopStmt.RepeatUntilStmt r -> rewriteBlock(r.body);
            default -> { /* no-op */ }
        }
    }

    private static void rewriteTryStmt(TryStmt s) {
        rewriteBlock(s.tryBlock);
        if (s.catches != null) {
            for (TryStmt.CatchClause c : s.catches) rewriteBlock(c.block());
        }
        if (s.finallyBlock != null) rewriteBlock(s.finallyBlock);
    }

    private static void rewriteMatchStmt(MatchStmt s) {
        if (s.cases != null) {
            for (MatchStmt.MatchCase c : s.cases) rewriteBlock((BlockStmt) c.body);
        }
    }

    // ----- Expression rewrites (for lambdas inside ExprStmts) -----

    private static void rewriteExpr(Expr expr) {
        if (expr instanceof LambdaExpr lambda && lambda.body != null) {
            rewriteBlock((BlockStmt) lambda.body);
        }
        if (expr instanceof CallExpr call) {
            for (CallExpr.Argument arg : call.arguments) rewriteExpr(arg.value);
        }
    }
}