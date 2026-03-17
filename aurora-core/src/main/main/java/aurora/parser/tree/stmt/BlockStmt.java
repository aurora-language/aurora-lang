package aurora.parser.tree.stmt;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;
import aurora.parser.tree.Statement;
import aurora.parser.tree.TypeNode;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a block of statements enclosed in braces {@code { ... }}.
 * Blocks create a new lexical scope for variables and are used in functions,
 * loops, and conditionals.
 */
public class BlockStmt extends Statement {
    /** The list of statements contained within this block, executed in order. */
    public final List<Statement> statements;
    public TypeNode returnType = new TypeNode();
    private static int indentLevel = 0;

    /**
     * @param loc Source location
     * @param statements List of statements in the block
     */
    public BlockStmt(SourceLocation loc, List<Statement> statements) {
        super(loc);
        this.statements = statements;
    }

    @Override
    public String toString() {
        if (statements.isEmpty()) return "{}";

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

        indentLevel++;
        for (var stmt : statements) {
            sb.append(" ".repeat(indentLevel * 4)).append(stmt.toString()).append("\n");
        }
        indentLevel--;

        sb.append(" ".repeat(indentLevel * 4)).append("}");
        return sb.toString();
    }
}
