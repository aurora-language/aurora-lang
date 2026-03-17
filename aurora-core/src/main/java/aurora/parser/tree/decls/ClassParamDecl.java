package aurora.parser.tree.decls;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Declaration;
import aurora.parser.tree.Expr;
import aurora.parser.tree.TypeNode;

/**
 * Represents a parameter in a class or record constructor that also defines a field.
 */
public class ClassParamDecl extends Declaration {
    /** The type of the parameter/field. */
    public final TypeNode type;

    /** The default value, if any. */
    public final Expr defaultValue;

    /** Indicates whether the generated field is immutable (val) or mutable (var). */
    public final boolean isImmutable;

    /**
     * @param loc Source location
     * @param name Parameter name
     * @param type Parameter type
     * @param defaultValue Default value (optional, null if none)
     * @param isImmutable True if this parameter declared with val
     */
    public ClassParamDecl(SourceLocation loc, String name, TypeNode type, Expr defaultValue, boolean isImmutable) {
        super(loc, name);
        this.type = type;
        this.defaultValue = defaultValue;
        this.isImmutable = isImmutable;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(isImmutable ? "val " : "var ");
        sb.append(name);
        if (type != null) sb.append(": ").append(type);
        if (defaultValue != null) sb.append(" = ").append(defaultValue);
        return sb.toString();
    }
}
