package aurora.parser.tree.stmt;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Statement;

/**
 * Represents an initialization block within a class.
 * These blocks execute when an instance is created or when the class is loaded (if static).
 */
public class InitializerBlock extends Statement {
    /** The block of code to execute during initialization. */
    public final BlockStmt body;

    /** Indicates whether this is a static initialization block. */
    public final boolean isStatic;
    
    public InitializerBlock(SourceLocation loc, BlockStmt body, boolean isStatic) {
        super(loc);
        this.body = body;
        this.isStatic = isStatic;
    }

    @Override
    public String toString() {
        return (isStatic ? "static " : "") + body;
    }
}
