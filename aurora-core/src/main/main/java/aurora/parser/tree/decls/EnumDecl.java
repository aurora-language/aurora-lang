package aurora.parser.tree.decls;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Declaration;
import aurora.parser.tree.Expr;
import aurora.parser.tree.util.Visibility;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents an enumeration declaration in Aurora.
 * Enums define a set of named constants and can behave like sum types (tagged unions).
 */
public class EnumDecl extends Declaration {
    /** The access level of the enum. */
    public final Visibility visibility;

    /** The list of constants (members) defined in this enum. */
    public final List<EnumMember> members;

    public EnumDecl(SourceLocation loc, String name, Visibility visibility, List<EnumMember> members) {
        super(loc, name);
        this.visibility = visibility;
        this.members = members;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (visibility != null) sb.append(visibility).append(" ");
        sb.append("enum ").append(name).append(" {\n");

        sb.append(members.stream()
                .map(m -> "    " + m.toString())
                .collect(Collectors.joining(",\n")));

        sb.append("\n}");
        return sb.toString();
    }

    public static class EnumMember extends Declaration {
        public final Expr value; // Optional assignment
        public final List<Declaration> bodyMembers; // Optional body

        public EnumMember(SourceLocation loc, String name, Expr value, List<Declaration> bodyMembers) {
            super(loc, name);
            this.value = value;
            this.bodyMembers = bodyMembers;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(name);
            if (value != null) {
                sb.append(" = ").append(value);
            }
            if (bodyMembers != null && !bodyMembers.isEmpty()) {
                sb.append(" {\n");
                for (Declaration decl : bodyMembers) {
                    String declStr = decl.toString();
                    String indentedMember = declStr.lines()
                            .map(line -> " ".repeat(4) + line)
                            .collect(Collectors.joining("\n"));

                    sb.append(indentedMember).append("\n");
                }
                sb.append("    }");
            }
            return sb.toString();
        }
    }
}
