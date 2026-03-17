package aurora.parser.tree.expr;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;
import aurora.parser.tree.decls.ParamDecl;
import aurora.parser.tree.stmt.BlockStmt;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents an inline thread expression that spawns a new execution context.
 */
public class ThreadExpr extends Expr {
    /** The name of the thread. */
    public final String name;

    /** The parameters passed to the thread body. */
    public final List<ParamDecl> params;

    /** The executable block for the thread. */
    public final BlockStmt body;
    
    public ThreadExpr(SourceLocation loc, String name, List<ParamDecl> params, BlockStmt body) {
        super(loc);
        this.name = name;
        this.params = params;
        this.body = body;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("thread ").append(name);
        if (params != null && !params.isEmpty()) {
            sb.append("(");
            sb.append(params.stream().map(ParamDecl::toString).collect(Collectors.joining(", ")));
            sb.append(")");
        }
        sb.append(" ").append(body);
        return sb.toString();
    }
}
