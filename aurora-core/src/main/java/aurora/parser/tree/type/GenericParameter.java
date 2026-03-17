package aurora.parser.tree.type;

import aurora.parser.tree.TypeNode;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a generic type parameter in a class, interface, record, or function declaration.
 * Includes information about the parameter's name, variance, upper bounds (constraints),
 * and an optional default type.
 */
public class GenericParameter {
    /** The name of the type parameter (e.g., "T"). */
    public final String name;

    /** The variance of the parameter (in, out, or none). */
    public final Variance variance;

    /** A list of upper bounds that the type argument must satisfy. */
    public final List<TypeNode> constraints;

    /** The default type to use if the parameter is not explicitly provided. May be null. */
    public final TypeNode defaultType;

    public GenericParameter(String name, Variance variance, List<TypeNode> constraints, TypeNode defaultType) {
        this.name = name;
        this.variance = variance != null ? variance : Variance.NONE;
        this.constraints = constraints != null ? constraints : Collections.emptyList();
        this.defaultType = defaultType;
    }

    public GenericParameter(String name) {
        this(name, Variance.NONE, Collections.emptyList(), null);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (variance == Variance.IN) {
            sb.append("in ");
        } else if (variance == Variance.OUT) {
            sb.append("out ");
        }
        sb.append(name);
        if (!constraints.isEmpty()) {
            sb.append(": ").append(constraints.stream().map(TypeNode::toString).collect(Collectors.joining(", ")));
        }
        if (defaultType != null) {
            sb.append(" default ").append(defaultType);
        }
        return sb.toString();
    }
}
