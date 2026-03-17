package aurora.parser.tree.decls;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Declaration;
import aurora.parser.tree.Expr;
import aurora.parser.tree.TypeNode;

/**
 * Represents a parameter in a function, method, or constructor declaration.
 */
public class ParamDecl extends Declaration {
    /** The type of the parameter. */
    public final TypeNode type;

    /** The default value assigned to the parameter if not provided by the caller. May be null. */
    public final Expr defaultValue;

    /** Indicates whether the parameter accepts a variable number of arguments. */
    public final boolean isVararg;

    /**
     * @param loc Source location
     * @param name Parameter name
     * @param type Parameter type
     * @param defaultValue Default value (optional, null if none)
     * @param isVararg True if this is a named parameter with default
     */
    public ParamDecl(SourceLocation loc, String name, TypeNode type, Expr defaultValue, boolean isVararg) {
        super(loc, name);
        this.type = type;
        this.defaultValue = defaultValue;
        this.isVararg = isVararg;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (isVararg) sb.append("varargs ");
        sb.append(name);
        if (type != null) sb.append(": ").append(type);
        if (defaultValue != null) sb.append(" = ").append(defaultValue);
        return sb.toString();
    }
}
