package aurora.parser.tree.expr;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;
import aurora.parser.tree.Node;
import aurora.parser.tree.decls.ParamDecl;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents an anonymous function (lambda expression).
 */
public class LambdaExpr extends Expr {
    /** The list of parameters defined for the lambda. */
    public final List<ParamDecl> params;

    /** The executable body of the lambda, which can be an expression or a block statement. */
    public final Node body;

    public LambdaExpr(SourceLocation loc, List<ParamDecl> params, Node body) {
        super(loc);
        this.params = params;
        this.body = body;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (params.size() == 1 && params.getFirst().type.name.equals("Any")) { // Simplified heuristic
            sb.append(params.getFirst().name);
        } else {
            sb.append("(");
            sb.append(params.stream().map(ParamDecl::toString).collect(Collectors.joining(", ")));
            sb.append(")");
        }
        sb.append(" => ").append(body);
        return sb.toString();
    }
}
