package aurora.parser.tree;

import aurora.parser.SourceLocation;

/**
 * Base class for all statement nodes in the AST.
 * A statement represents an action or a command that does not necessarily produce a value.
 */
public abstract class Statement extends Node {
    /**
     * Initializes a statement node.
     *
     * @param loc The location in the source code.
     */
    public Statement(SourceLocation loc) {
        super(loc);
    }
}
