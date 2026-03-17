package aurora.parser.tree;

import aurora.parser.SourceLocation;

/**
 * Base class for all expression nodes in the AST.
 * An expression is a node that can be evaluated to produce a value.
 */
public abstract class Expr extends Node {
    /**
     * Initializes an expression node.
     *
     * @param loc The location in the source code.
     */
    public Expr(SourceLocation loc) {
        super(loc);
    }
}
