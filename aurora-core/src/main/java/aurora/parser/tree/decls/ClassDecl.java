package aurora.parser.tree.decls;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Declaration;
import aurora.parser.tree.TypeNode;
import aurora.parser.tree.type.GenericParameter;
import aurora.parser.tree.util.Visibility;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a class declaration in Aurora.
 * A class can have visibility modifiers, generic type parameters, a superclass,
 * implemented interfaces, and a list of members (fields, methods, constructors, etc.).
 */
public class ClassDecl extends Declaration {
    /** The access level of this class. */
    public final Visibility visibility;

    /** Indicates whether this class is abstract and cannot be instantiated directly. */
    public final boolean isAbstract;

    /** The generic type parameters for this class. */
    public final List<GenericParameter> typeParams;

    /** The class being extended by this class. May be null. */
    public final TypeNode superClass;

    /** The interfaces (traits) implemented by this class. */
    public final List<TypeNode> interfaces;

    /** The fields, methods, and other declarations contained within this class. */
    public final List<Declaration> members;

    /**
     * @param loc Source location
     * @param name Class name
     * @param visibility Visibility modifier
     * @param isAbstract True if class is abstract
     * @param typeParams Generic type parameters
     * @param superClass Superclass type (optional)
     * @param interfaces Implemented interfaces
     * @param members List of members (fields, methods, etc.)
     */
    public ClassDecl(SourceLocation loc, String name, Visibility visibility, boolean isAbstract,
                     List<GenericParameter> typeParams, TypeNode superClass, List<TypeNode> interfaces,
                     List<Declaration> members) {
        super(loc, name);
        this.visibility = visibility;
        this.isAbstract = isAbstract;
        this.typeParams = typeParams != null ? typeParams : Collections.emptyList();
        this.superClass = superClass;
        this.interfaces = interfaces != null ? interfaces : Collections.emptyList();
        this.members = members != null ? members : Collections.emptyList();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (visibility != null) sb.append(visibility).append(" ");
        if (isAbstract) sb.append("abstract ");
        sb.append("class ").append(name);
        if (!typeParams.isEmpty()) {
            sb.append("<").append(typeParams.stream().map(GenericParameter::toString).collect(Collectors.joining(", "))).append(">");
        }
        if (superClass != null || !interfaces.isEmpty()) {
            sb.append(" : ");
        }
        if (superClass != null) {
            sb.append(superClass);
            if (!interfaces.isEmpty()) {
                sb.append(", ");
            }
        }
        if (!interfaces.isEmpty()) {
            sb.append(interfaces.stream().map(TypeNode::toString).collect(Collectors.joining(", ")));
        }
        sb.append(" {\n");
        for (Declaration decl : members) {
            String declStr = decl.toString();
            String indentedMember = declStr.lines()
                    .map(line -> " ".repeat(4) + line)
                    .collect(Collectors.joining("\n"));

            sb.append(indentedMember).append("\n");
        }
        sb.append("}");
        return sb.toString();
    }
}
