package aurora.parser.tree.decls;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Declaration;
import aurora.parser.tree.TypeNode;
import aurora.parser.tree.util.Visibility;
import aurora.parser.tree.type.GenericParameter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a record declaration in Aurora.
 * Records are concise, data-centric classes that automatically generate boilerplate
 * like constructors and field accessors based on their parameters.
 */
public class RecordDecl extends Declaration {
    /** The access level of the record. */
    public final Visibility visibility;

    /** The generic type parameters for the record. */
    public final List<GenericParameter> typeParams;

    /** The parameters that define the record's primary constructor and fields. */
    public final List<ClassParamDecl> parameters;

    /** The interfaces implemented by the record. */
    public final List<TypeNode> implementsInterfaces;

    /** Any additional methods or static members declared within the record body. */
    public final List<Declaration> members;

    /**
     * @param loc Source location
     * @param name Record name
     * @param visibility Visibility
     * @param typeParams Generic type parameters
     * @param parameters Record constructor parameters
     * @param implementsInterfaces Interfaces implemented
     * @param members Inner members (methods/fields)
     */
    public RecordDecl(SourceLocation loc, String name, Visibility visibility,
                      List<GenericParameter> typeParams,
                      List<ClassParamDecl> parameters, List<TypeNode> implementsInterfaces,
                      List<Declaration> members) {
        super(loc, name);
        this.visibility = visibility;
        this.typeParams = typeParams != null ? typeParams : java.util.Collections.emptyList();
        this.parameters = parameters;
        this.implementsInterfaces = implementsInterfaces;
        this.members = members;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (visibility != null) sb.append(visibility).append(" ");
        sb.append("record ").append(name);
        
        if (typeParams != null && !typeParams.isEmpty()) {
            sb.append("<").append(typeParams.stream().map(GenericParameter::toString).collect(Collectors.joining(", "))).append(">");
        }
        
        sb.append("(");

        sb.append(parameters.stream()
                .map(ClassParamDecl::toString)
                .collect(Collectors.joining(", ")));

        sb.append(")");

        if (implementsInterfaces != null && !implementsInterfaces.isEmpty()) {
            sb.append(" : ").append(implementsInterfaces.stream()
                    .map(TypeNode::toString)
                    .collect(Collectors.joining(", ")));
        }

        if (members != null && !members.isEmpty()) {
            sb.append(" {\n");
            for (Declaration decl : members) {
                String declStr = decl.toString();
                String indentedMember = declStr.lines()
                        .map(line -> " ".repeat(4) + line)
                        .collect(Collectors.joining("\n"));

                sb.append(indentedMember).append("\n");
            }
            sb.append("}");
        } else {
            sb.append(" {}");
        }

        return sb.toString();
    }
}
