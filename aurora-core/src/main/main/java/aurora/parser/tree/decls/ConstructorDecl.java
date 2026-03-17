package aurora.parser.tree.decls;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Declaration;
import aurora.parser.tree.stmt.BlockStmt;
import aurora.parser.tree.util.Visibility;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a constructor declaration within a class.
 * Constructors are used to initialize new instances of a class.
 */
public class ConstructorDecl extends Declaration {
    /** The access level of the constructor. */
    public final Visibility visibility;

    /** The list of parameters accepted by the constructor. */
    public final List<ParamDecl> params;

    /** The executable body of the constructor. */
    public final BlockStmt body;
    
    public ConstructorDecl(SourceLocation loc, Visibility visibility, List<ParamDecl> params, BlockStmt body) {
        super(loc, "<init>"); // Name is always constructor
        this.visibility = visibility;
        this.params = params;
        this.body = body;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (visibility != null) sb.append(visibility).append(" ");
        sb.append("constructor(");
        if (params != null) {
            sb.append(params.stream().map(Object::toString).collect(Collectors.joining(", ")));
        }
        sb.append(") ").append(body);
        return sb.toString();
    }
}
