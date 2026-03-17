package aurora.parser.tree.decls;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Declaration;
import aurora.parser.tree.TypeNode;
import aurora.parser.tree.stmt.BlockStmt;
import aurora.parser.tree.type.GenericParameter;
import aurora.parser.tree.util.FunctionModifier;
import aurora.parser.tree.util.Visibility;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a function or method declaration in Aurora.
 * This node handles various function forms, including top-level functions,
 * class methods, and thread entry points, with support for asynchronous execution,
 * generic parameters, and expression-style bodies.
 */
public class FunctionDecl extends Declaration {
    /** The access level of the function. */
    public final Visibility visibility;

    /** A list of modifiers applied to the function (e.g., async, static). */
    public final List<FunctionModifier> modifiers;

    /** The generic type parameters for the function. */
    public final List<GenericParameter> typeParams;

    /** The parameters accepted by the function. */
    public final List<ParamDecl> params;

    /** The return type of the function. */
    public final TypeNode returnType;

    /** The executable body of the function. */
    public final BlockStmt body;

    /** Indicates whether the body is a single expression (e.g., used with the fat arrow syntax). */
    public final boolean isExpressionBody;

    /** The target receiver type if this is an extension function. */
    public final TypeNode receiverType;

    /**
     * @param loc              Source location
     * @param name             Function name
     * @param receiverType     Optional receiver type for extension functions
     * @param visibility       Visibility modifier
     * @param modifiers        List of function modifiers (async, static, etc.)
     * @param typeParams       Generic parameter list
     * @param params           Parameter list
     * @param returnType       Return type (can be void/none)
     * @param body             Function body (block)
     * @param isExpressionBody True if body is a single expression (fat arrow)
     */
    public FunctionDecl(SourceLocation loc, String name, TypeNode receiverType, Visibility visibility,
            List<FunctionModifier> modifiers, List<GenericParameter> typeParams, List<ParamDecl> params,
            TypeNode returnType, BlockStmt body, boolean isExpressionBody) {
        super(loc, name);
        this.receiverType = receiverType;
        this.visibility = visibility;
        this.modifiers = modifiers;
        this.typeParams = typeParams != null ? typeParams : List.of();
        this.params = params;
        this.returnType = returnType;
        this.body = body;
        this.isExpressionBody = isExpressionBody;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (visibility != null)
            sb.append(visibility).append(" ");
        if (modifiers != null) {
            for (FunctionModifier mod : modifiers) {
                sb.append(mod).append(" ");
            }
        }
        sb.append("fun ");
        if (receiverType != null) {
            sb.append(receiverType).append(".");
        }
        sb.append(name);
        if (typeParams != null && !typeParams.isEmpty()) {
            sb.append("<").append(typeParams.stream().map(GenericParameter::toString).collect(Collectors.joining(", "))).append(">");
        }
        sb.append("(");
        if (params != null) {
            sb.append(params.stream().map(Object::toString).collect(Collectors.joining(", ")));
        }
        sb.append("): ").append(returnType != null ? returnType : "Void");

        if (isExpressionBody) {
            sb.append(" => ").append(body);
        } else if (body != null){
            sb.append(" ").append(body);
        }
        return sb.toString();
    }
}
