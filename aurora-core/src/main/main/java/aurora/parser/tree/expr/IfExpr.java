package aurora.parser.tree.expr;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;
import aurora.parser.tree.stmt.BlockStmt;
import aurora.parser.tree.stmt.IfStmt;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a conditional expression (if-else) that evaluates to a value.
 * Unlike an {@link IfStmt}, an {@code IfExpr} must always have an {@code else} branch
 * (or eventually evaluate to a result) in most contexts.
 */
public class IfExpr extends Expr {
    /** The condition to evaluate. */
    public final Expr condition;

    /** The block to execute if the condition is true. */
    public final BlockStmt thenBlock;

    /** A list of additional conditions to check if the primary condition is false. */
    public final List<IfStmt.ElseIf> elseIfs;

    /** The block to execute if all conditions are false. */
    public final BlockStmt elseBlock;

    public IfExpr(SourceLocation loc, Expr condition, BlockStmt thenBlock,
                  List<IfStmt.ElseIf> elseIfs, BlockStmt elseBlock) {
        super(loc);
        this.condition = condition;
        this.thenBlock = thenBlock;
        this.elseIfs = elseIfs != null ? elseIfs : Collections.emptyList();
        this.elseBlock = elseBlock;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("if ").append(condition).append(" ").append(thenBlock);
        for (IfStmt.ElseIf elif : elseIfs) {
            sb.append(elif);
        }
        if (elseBlock != null) {
            sb.append(" else ").append(elseBlock);
        }
        return sb.toString();
    }
}
