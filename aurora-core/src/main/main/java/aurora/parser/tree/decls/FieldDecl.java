package aurora.parser.tree.decls;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Declaration;
import aurora.parser.tree.Expr;
import aurora.parser.tree.TypeNode;
import aurora.parser.tree.util.Visibility;

/**
 * Represents a field (variable or constant) declaration within a class, interface, or at the top level.
 */
public class FieldDecl extends Declaration {
    /** The access level of the field. */
    public final Visibility visibility;

    /** Indicates whether the field is shared across all instances (static). */
    public final boolean _static;

    /** The mutability type of the field (VAR for mutable, VAL for immutable). */
    public final Type declType;

    /** The explicit type of the field. May be null if inferred. */
    public final TypeNode type;

    /** The initial value of the field. May be null. */
    public final Expr init;

    public FieldDecl(SourceLocation loc, String name, Visibility visibility, boolean aStatic, Type declType, TypeNode type, Expr init) {
        super(loc, name);
        this.visibility = visibility;
        _static = aStatic;
        this.declType = declType;
        this.type = type;
        this.init = init;
    }

    public enum Type {
        VAR,
        VAL
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (visibility != null) sb.append(visibility).append(" ");
        if (_static) sb.append("static ");
        sb.append(declType.name().toLowerCase()).append(" ").append(name);
        if (type != null) sb.append(": ").append(type);
        if (init != null) sb.append(" = ").append(init);
        return sb.toString();
    }
}
