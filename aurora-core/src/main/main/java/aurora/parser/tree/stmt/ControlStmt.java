package aurora.parser.tree.stmt;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;
import aurora.parser.tree.Statement;

/**
 * Abstract base class for control flow statements like return, throw, break, and continue.
 */
public abstract class ControlStmt extends Statement {
    public ControlStmt(SourceLocation loc) { super(loc); }

    public static class ReturnStmt extends ControlStmt {
        public final Expr value;
        public ReturnStmt(SourceLocation loc, Expr value) {
            super(loc);
            this.value = value;
        }
        @Override
        public String toString() {
            return "return" + (value != null ? " " + value : "");
        }
    }

    public static class ThrowStmt extends ControlStmt {
        public final Expr value;
        public ThrowStmt(SourceLocation loc, Expr value) {
            super(loc);
            this.value = value;
        }
        @Override
        public String toString() {
            return "throw " + value;
        }
    }

    public static class BreakStmt extends ControlStmt {
        public final String label;
        public BreakStmt(SourceLocation loc, String label) {
            super(loc);
            this.label = label;
        }
        @Override
        public String toString() {
            return "break" + (label != null ? " @" + label : "");
        }
    }

    public static class ContinueStmt extends ControlStmt {
        public final String label;
        public ContinueStmt(SourceLocation loc, String label) {
            super(loc);
            this.label = label;
        }
        @Override
        public String toString() {
            return "continue" + (label != null ? " @" + label : "");
        }
    }
}
