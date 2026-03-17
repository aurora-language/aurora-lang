package aurora.parser.tree.decls;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Declaration;
import aurora.parser.tree.stmt.BlockStmt;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a thread entry point declaration.
 * Threads define a block of code that can be executed concurrently with other parts of the program.
 */
public class ThreadDecl extends Declaration {
    /** The parameters passed to the thread upon entry. */
    public final List<ParamDecl> params;

    /** The executable code block that runs when the thread starts. */
    public final BlockStmt body;

    public ThreadDecl(SourceLocation loc, String name, List<ParamDecl> params, BlockStmt body) {
        super(loc, name);
        this.params = params;
        this.body = body;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("thread ").append(name).append("(");
        if (params != null) {
            sb.append(params.stream().map(Object::toString).collect(Collectors.joining(", ")));
        }
        sb.append(") ").append(body);
        return sb.toString();
    }
}
