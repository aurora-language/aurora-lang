package aurora.parser.tree.decls;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Declaration;
import aurora.parser.tree.TypeNode;
import aurora.parser.tree.util.Visibility;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a method signature used within an interface (trait) declaration.
 * Signatures define the name, parameters, and return type of a method without providing an implementation.
 */
public class MethodSignature extends Declaration {
    /** The access level of the method. */
    public final Visibility visibility;

    /** Indicates whether the method is asynchronous. */
    public final boolean isAsync;

    /** The generic type parameter names. */
    public final List<String> typeParams;

    /** The parameters required by the method. */
    public final List<ParamDecl> params;

    /** The return type of the method. */
    public final TypeNode returnType;
    
    public MethodSignature(SourceLocation loc, String name, Visibility visibility, boolean isAsync,
                          List<String> typeParams, List<ParamDecl> params, TypeNode returnType) {
        super(loc, name);
        this.visibility = visibility;
        this.isAsync = isAsync;
        this.typeParams = typeParams != null ? typeParams : Collections.emptyList();
        this.params = params != null ? params : Collections.emptyList();
        this.returnType = returnType;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (visibility != null) sb.append(visibility).append(" ");
        if (isAsync) sb.append("async ");
        sb.append("fun ").append(name);
        if (!typeParams.isEmpty()) {
            sb.append("<").append(String.join(", ", typeParams)).append(">");
        }
        sb.append("(");
        sb.append(params.stream().map(Object::toString).collect(Collectors.joining(", ")));
        sb.append("): ").append(returnType);
        return sb.toString();
    }
}
