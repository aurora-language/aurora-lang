package aurora.parser.tree;

import aurora.parser.SourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * The base class for all nodes in the Aurora Abstract Syntax Tree (AST).
 * Every node tracks its position in the source code and can hold comments
 * that were attached to it during the parsing process.
 */
public abstract class Node {
    /** The source location where this node was defined. */
    public final SourceLocation loc;

    /** A list of comments that appeared immediately before this node in the source code. */
    public List<String> leadingComments = new ArrayList<>();

    /** A list of comments that appeared on the same line or immediately after this node. */
    public List<String> trailingComments = new ArrayList<>();

    /**
     * Initializes a node with its source location.
     *
     * @param loc The location in the source code.
     */
    public Node(SourceLocation loc) {
        this.loc = loc;
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName();
    }
}
