package aurora.parser.tree.expr;

import aurora.parser.SourceLocation;
import aurora.parser.tree.Expr;

/**
 * Represents a member or field access expression.
 * Handles standard access (.), safe navigation (?.), and static member access (::).
 */
public class AccessExpr extends Expr {
    /** The object or class being accessed. May be null for simple identifiers. */
    public final Expr object;

    /** The name of the member being accessed. */
    public final String member;

    /** Indicates whether this is a safe navigation access (e.g., {@code obj?.member}). */
    public final boolean isSafe;

    /** Indicates whether this is a static member access (e.g., {@code Class::member}). */
    public final boolean isStatic;

    /** The exact source location of the member identifier. */
    public SourceLocation memberLoc;

    public AccessExpr(SourceLocation loc, Expr object, String member, boolean isSafe, boolean isStatic,
            SourceLocation memberLoc) {
        super(loc);
        this.object = object;
        this.member = member;
        this.isSafe = isSafe;
        this.isStatic = isStatic;
        this.memberLoc = memberLoc;
    }

    public AccessExpr(SourceLocation loc, Expr object, String member, boolean isSafe, boolean isStatic) {
        this(loc, object, member, isSafe, isStatic, null);
    }

    public AccessExpr(SourceLocation loc, Expr object, String member, boolean isSafe) {
        this(loc, object, member, isSafe, false, null);
    }

    @Override
    public String toString() {
        String connector = isSafe ? "?." : isStatic ? "::" : ".";
        if (object == null)
            return member;
        return object + connector + member;
    }
}
