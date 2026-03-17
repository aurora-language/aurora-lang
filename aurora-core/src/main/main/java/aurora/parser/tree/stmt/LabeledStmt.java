package aurora.parser.tree.stmt;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Statement;

/**
 * Represents a statement with an associated label (e.g., {@code label: statement}).
 * Labels are typically used with {@code break} and {@code continue} to control
 * nested loops.
 */
public class LabeledStmt extends Statement {
    /** The name of the label. */
    public final String label;

    /** The statement being labeled. */
    public final Statement statement;
    
    public LabeledStmt(SourceLocation loc, String label, Statement statement) {
        super(loc);
        this.label = label;
        this.statement = statement;
    }

    @Override
    public String toString() {
        return label + ": " + statement;
    }
}
