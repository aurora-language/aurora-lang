package aurora.parser.tree.type;

/**
 * Defines the subtyping relationship (variance) for generic type parameters.
 */
public enum Variance {
    /** Contravariant (input-only): allows the type to be used as a more general type. */
    IN,
    /** Covariant (output-only): allows the type to be used as a more specific type. */
    OUT,
    /** Invariant (the default): the type must match exactly. */
    NONE
}
