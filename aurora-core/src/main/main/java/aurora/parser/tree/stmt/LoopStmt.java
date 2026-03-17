package aurora.parser.tree.stmt;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;
import aurora.parser.tree.Statement;

/**
 * Abstract base class for all loop statements in Aurora.
 */
public abstract class LoopStmt extends Statement {
    /** The body of the loop to be executed repeatedly. */
    public final BlockStmt body;

    public LoopStmt(SourceLocation loc, BlockStmt body) {
        super(loc);
        this.body = body;
    }

    public static class WhileStmt extends LoopStmt {
        public final Expr condition;
        public WhileStmt(SourceLocation loc, Expr condition, BlockStmt body) {
            super(loc, body);
            this.condition = condition;
        }

        @Override
        public String toString() {
            return "while (" + condition + ") " + body;
        }
    }

    public static class RepeatUntilStmt extends LoopStmt {
        public final Expr condition;
        public RepeatUntilStmt(SourceLocation loc, BlockStmt body, Expr condition) {
            super(loc, body);
            this.condition = condition;
        }

        @Override
        public String toString() {
            return "repeat " + body + " until (" + condition + ")";
        }
    }

    public static class ForStmt extends LoopStmt {
        public final String varName;
        public final Expr iterable;
        public ForStmt(SourceLocation loc, String varName, Expr iterable, BlockStmt body) {
            super(loc, body);
            this.varName = varName;
            this.iterable = iterable;
        }

        @Override
        public String toString() {
            return "for (" + varName + " in " + iterable + ") " + body;
        }
    }
}