package aurora.parser.tree;

import aurora.parser.SourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a type reference in the Aurora source code.
 * This can be a primitive type, a class name, or a complex type with generic arguments and suffixes.
 */
public class TypeNode extends Node {
    public final SourceLocation nameLoc;

    /** The name of the type (e.g., "int", "List"). */
    public final String name;

    /** Indicates whether this is a primitive built-in type. */
    public final boolean primitive;

    /** The generic type arguments (e.g., the "T" in "List<T>"). */
    public final List<TypeNode> typeArguments;

    /** A list of suffixes applied to the type, such as array brackets or nullability markers. */
    public final List<TypeSuffix> suffixes;

    /**
     * Initializes a comprehensive TypeNode.
     *
     * @param loc           The location in the source code.
     * @param name          The name of the type.
     * @param typeArguments The generic arguments.
     * @param suffixes      The type suffixes.
     */
    public TypeNode(SourceLocation loc, SourceLocation nameLoc, String name, List<TypeNode> typeArguments, List<TypeSuffix> suffixes) {
        super(loc);
        this.nameLoc = nameLoc;
        this.name = name;
        this.typeArguments = typeArguments != null ? typeArguments : Collections.emptyList();
        this.suffixes = suffixes != null ? suffixes : Collections.emptyList();
        this.primitive = isPrimitive(name);
    }

    public TypeNode(SourceLocation loc, SourceLocation nameLoc, String name) {
        super(loc);
        this.nameLoc = nameLoc;
        this.name = name;
        this.typeArguments = Collections.emptyList();
        this.suffixes = Collections.emptyList();
        this.primitive = isPrimitive(name);
    }

    public TypeNode(SourceLocation loc, String name) {
        super(loc);
        this.nameLoc = loc;
        this.name = name;
        this.typeArguments = Collections.emptyList();
        this.suffixes = Collections.emptyList();
        this.primitive = isPrimitive(name);
    }

    public TypeNode(SourceLocation loc) {
        super(loc);
        this.nameLoc = loc;
        this.name = "none";
        this.typeArguments = Collections.emptyList();
        this.suffixes = Collections.emptyList();
        this.primitive = true;
    }

    public TypeNode() {
        super(new SourceLocation());
        this.nameLoc = new SourceLocation();
        this.name = "none";
        this.typeArguments = Collections.emptyList();
        this.suffixes = Collections.emptyList();
        this.primitive = true;
    }

    public static class Lambda extends TypeNode {
        public final List<TypeNode> params;
        public final TypeNode ret;

        public Lambda(SourceLocation loc, List<TypeNode> params, TypeNode ret) {
            super(loc, loc, "<lambda>");
            this.params = params;
            this.ret = ret;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (params.size() == 1 && params.getFirst().name.equals("Any")) { // Simplified heuristic
                sb.append(params.getFirst().name);
            } else {
                sb.append("(");
                sb.append(params.stream().map(TypeNode::toString).collect(Collectors.joining(", ")));
                sb.append(")");
            }
            sb.append(" => ").append(ret);
            return sb.toString();
        }
    }

    private static boolean isPrimitive(String name) {
        return switch (name) {
            case "void", "bool", "int", "long", "float", "double", "string", "object", "none" -> true;
            default -> false;
        };
    }

    public sealed interface TypeSuffix permits TypeSuffix.Nullable, TypeSuffix.Array, TypeSuffix.SizedArray {
        final class Nullable implements TypeSuffix {
            @Override public String toString() { return "?"; }
        }
        final class Array implements TypeSuffix {
            @Override public String toString() { return "[]"; }
        }

        record SizedArray(int size) implements TypeSuffix {
            @Override
            public @NotNull String toString() {
                return "[" + size + "]";
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(name);
        if (!typeArguments.isEmpty()) {
            sb.append("<");
            sb.append(typeArguments.stream().map(Node::toString).collect(Collectors.joining(", ")));
            sb.append(">");
        }
        for (TypeSuffix suffix : suffixes) {
            sb.append(suffix.toString());
        }
        return sb.toString();
    }
}
