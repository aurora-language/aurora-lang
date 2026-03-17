package aurora.parser.tree.expr;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;
import aurora.parser.tree.stmt.MatchStmt;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a pattern matching expression that returns a value.
 */
public class MatchExpr extends Expr {
    /** The expression being matched against the patterns. */
    public final Expr expression;

    /** The list of cases to check during the match. */
    public final List<MatchStmt.MatchCase> cases;

    public MatchExpr(SourceLocation loc, Expr expression, List<MatchStmt.MatchCase> cases) {
        super(loc);
        this.expression = expression;
        this.cases = cases;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("match ").append(expression).append(" {\n");
        for (MatchStmt.MatchCase c : cases) {
            String caseStr = c.toString();
            String indentedCases = caseStr.lines()
                    .map(line -> " ".repeat(4) + line)
                    .collect(Collectors.joining("\n"));

            sb.append(indentedCases).append("\n");
        }
        sb.append("}");
        return sb.toString();
    }
}