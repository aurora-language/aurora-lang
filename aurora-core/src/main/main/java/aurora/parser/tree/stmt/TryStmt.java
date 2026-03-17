package aurora.parser.tree.stmt;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Statement;
import aurora.parser.tree.TypeNode;

import java.util.List;

/**
 * Represents a try-catch-finally statement for exception handling.
 */
public class TryStmt extends Statement {
    /** The block containing the code to monitor for exceptions. */
    public final BlockStmt tryBlock;

    /** The list of catch clauses that handle specific exception types. */
    public final List<CatchClause> catches; // Can be empty if finally exists

    /** The block that always executes, regardless of whether an exception occurred. */
    public final BlockStmt finallyBlock; // Can be null

    public TryStmt(SourceLocation loc, BlockStmt tryBlock, List<CatchClause> catches, BlockStmt finallyBlock) {
        super(loc);
        this.tryBlock = tryBlock;
        this.catches = catches;
        this.finallyBlock = finallyBlock;
    }

    public record CatchClause(String var, TypeNode type, BlockStmt block) {}

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("try ").append(tryBlock);

        for (CatchClause catchClause : catches) {
            sb.append(" catch (")
                    .append(catchClause.type())
                    .append(" ")
                    .append(catchClause.var())
                    .append(") ")
                    .append(catchClause.block());
        }

        if (finallyBlock != null) {
            sb.append(" finally ").append(finallyBlock);
        }

        return sb.toString();
    }
}
