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
 * Represents an interface (or trait) declaration in Aurora.
 * Interfaces define a set of methods that a class must implement, enabling polymorphic behavior.
 */
public class InterfaceDecl extends Declaration {
    /** The access level of the interface. */
    public final Visibility visibility;

    /** The generic type parameters for the interface. */
    public final List<GenericParameter> typeParams;

    /** A list of other interfaces that this interface extends. */
    public final List<TypeNode> interfaces;

    /** The method signatures or default implementations contained within the interface. */
    public final List<Declaration> members;

    /**
     * @param loc        Source location
     * @param name       Trait name
     * @param visibility Visibility modifier
     * @param typeParams Generic parameters
     * @param interfaces Super traits
     * @param members    Members
     */
    public InterfaceDecl(SourceLocation loc, String name, Visibility visibility,
            List<GenericParameter> typeParams, List<TypeNode> interfaces, List<Declaration> members) {
        super(loc, name);
        this.visibility = visibility;
        this.typeParams = typeParams != null ? typeParams : Collections.emptyList();
        this.interfaces = interfaces != null ? interfaces : Collections.emptyList();
        this.members = members != null ? members : Collections.emptyList();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (visibility != null)
            sb.append(visibility).append(" ");
        sb.append("trait ").append(name);
        if (!typeParams.isEmpty()) {
            sb.append("<").append(typeParams.stream().map(GenericParameter::toString).collect(Collectors.joining(", "))).append(">");
        }
        if (!interfaces.isEmpty()) {
            sb.append(" extends ")
                    .append(interfaces.stream().map(TypeNode::toString).collect(Collectors.joining(", ")));
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
