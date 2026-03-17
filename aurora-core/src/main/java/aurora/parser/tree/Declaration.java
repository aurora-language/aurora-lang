package aurora.parser.tree;

import aurora.parser.SourceLocation;

/**
 * Base class for all declaration nodes.
 * A declaration is a specialized statement that introduces a named entity
 * (like a variable, function, or class) into a scope.
 */
public abstract class Declaration extends Statement {
    /** The name of the entity being declared. */
    public final String name;

    /** The exact source location of the identifier name. */
    public SourceLocation nameLoc = null;

    /**
     * Initializes a declaration node.
     *
     * @param loc  The location of the entire declaration.
     * @param name The name of the entity.
     */
    public Declaration(SourceLocation loc, String name) {
        super(loc);
        this.name = name;
    }
}
