package aurora.parser.tree.stmt;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;
import aurora.parser.tree.Statement;

import java.util.Collections;
import java.util.List;

/**
 * Represents a conditional statement (if-elseif-else).
 */
public class IfStmt extends Statement {
    /** The condition to evaluate. */
    public final Expr condition;

    /** The block to execute if the condition is true. */
    public final BlockStmt thenBlock;

    /** A list of additional conditions and blocks to check if the primary condition is false. */
    public final List<ElseIf> elseIfs;

    /** The block to execute if all conditions are false. May be null. */
    public final BlockStmt elseBlock;

    public IfStmt(SourceLocation loc, Expr condition, BlockStmt thenBlock, List<ElseIf> elseIfs, BlockStmt elseBlock) {
        super(loc);
        this.condition = condition;
        this.thenBlock = thenBlock;
        this.elseIfs = elseIfs != null ? elseIfs : Collections.emptyList();
        this.elseBlock = elseBlock;
    }

    public static class ElseIf extends Statement {
        public final Expr condition;
        public final BlockStmt block;

        public ElseIf(SourceLocation loc, Expr condition, BlockStmt block) {
            super(loc);
            this.condition = condition;
            this.block = block;
        }

        @Override
        public String toString() {
            return " elseif " + condition + " " + block;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("if ").append(condition).append(" ").append(thenBlock);
        for (ElseIf elif : elseIfs) {
            sb.append(elif);
        }
        if (elseBlock != null) {
            sb.append(" else ").append(elseBlock);
        }
        return sb.toString();
    }
}
