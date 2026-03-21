package aurora.analyzer;

import aurora.parser.SourceLocation;
import aurora.parser.tree.*;
import aurora.parser.tree.decls.*;
import aurora.parser.tree.expr.*;
import aurora.parser.tree.stmt.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A visitor that locates the deepest AST node containing a specific source coordinate.
 * Used for features like "Go to Definition" and "Hover" to find what the user is interacting with.
 */
public class NodeFinder implements NodeVisitor<Void> {
    /** The 1-based line number of the target position. */
    private final int line;

    /** The 0-based column number of the target position. */
    private final int column;

    /** A stack used during traversal to build the path from root to target node. */
    private final Deque<Node> pathStack = new ArrayDeque<>();

    /** The final path of nodes from the root down to the deepest node at the target position. */
    private List<Node> foundPath = null;

    public NodeFinder(int line, int column) {
        this.line = line;
        this.column = column;
    }

    public List<Node> findPath(Node root) {
        if (root == null)
            return null;
        if (!contains(root.loc))
            return null;

        pathStack.clear();
        foundPath = null;

        visit(root);

        // ArrayDeque.push() = addFirst(), so the stack is stored deepest-first.
        // Reverse so the result is [root, ..., deepestNode] (parent → child order).
        if (foundPath != null) {
            java.util.Collections.reverse(foundPath);
        }
        return foundPath;
    }

    public Node find(Node root) {
        List<Node> path = findPath(root);
        return path != null && !path.isEmpty() ? path.get(path.size() - 1) : null;
    }

    private void visit(Node node) {
        if (node == null)
            return;

        pathStack.push(node);
        if (contains(node.loc)) {
            foundPath = new ArrayList<>(pathStack);
        }

        if (node instanceof Program)
            visitProgram((Program) node);
        else if (node instanceof Statement)
            visitStatement((Statement) node);
        else if (node instanceof Expr)
            visitExpr((Expr) node);
        else if (node instanceof Declaration)
            visitDeclaration((Declaration) node);
        else if (node instanceof TypeNode)
            visitTypeNode((TypeNode) node);

        pathStack.pop();
    }

    private void check(Node node) {
        if (node != null && contains(node.loc)) {
            visit(node);
        }
    }

    private void check(List<? extends Node> nodes) {
        if (nodes == null)
            return;
        for (Node node : nodes) {
            check(node);
        }
    }

    /**
     * Visits a TypeNode directly, using the cursor position to decide whether to
     * include it.
     * TypeNodes like superclass references have correct token-level locs (e.g.
     * "Animal" token),
     * so we check if the cursor is within their col range on their start line.
     */
    private void checkTypeNode(TypeNode typeNode) {
        if (typeNode == null || typeNode.loc == null)
            return;
        if (!contains(typeNode.loc))
            return;
        // Push as a leaf (TypeNode has no children to recurse into)
        pathStack.push(typeNode);
        foundPath = new ArrayList<>(pathStack);
        // Reverse-in-place: foundPath will be reversed when findPath() returns
        pathStack.pop();
    }

    private boolean contains(SourceLocation loc) {
        if (loc == null)
            return false;
        if (line < loc.line() || line > loc.endLine())
            return false;
        if (line == loc.line() && column < loc.column())
            return false;
        if (line == loc.endLine() && column > loc.endColumn())
            return false;
        return true;
    }

    // --- Visitor Implementation ---

    @Override
    public Void visitNode(Node node) {
        return null;
    }

    @Override
    public Void visitProgram(Program program) {
        if (program.aPackage != null)
            check(program.aPackage);
        if (program.imports != null)
            check(program.imports);
        check(program.statements);
        return null;
    }

    @Override
    public Void visitProgramPackage(Program.Package pkg) {
        return null;
    }

    @Override
    public Void visitProgramImport(Program.Import imp) {
        return null;
    }

    @Override
    public Void visitProgramImportWildCard(Program.ImportWildCard imp) {
        return null;
    }

    @Override
    public Void visitProgramImportAlias(Program.ImportAlias imp) {
        return null;
    }

    @Override
    public Void visitProgramImportMulti(Program.ImportMulti imp) {
        return null;
    }

    @Override
    public Void visitStatement(Statement stmt) {
        if (stmt instanceof ExprStmt)
            return visitExprStmt((ExprStmt) stmt);
        if (stmt instanceof BlockStmt)
            return visitBlockStmt((BlockStmt) stmt);
        if (stmt instanceof IfStmt)
            return visitIfStmt((IfStmt) stmt);
        if (stmt instanceof LoopStmt)
            return visitLoopStmt((LoopStmt) stmt);
        if (stmt instanceof ControlStmt)
            return visitControlStmt((ControlStmt) stmt);
        if (stmt instanceof TryStmt)
            return visitTryStmt((TryStmt) stmt);
        if (stmt instanceof LabeledStmt)
            return visitLabeledStmt((LabeledStmt) stmt);
        if (stmt instanceof Declaration)
            return visitDeclaration((Declaration) stmt);
        return null;
    }

    @Override
    public Void visitExpr(Expr expr) {
        if (expr instanceof BinaryExpr)
            return visitBinaryExpr((BinaryExpr) expr);
        if (expr instanceof UnaryExpr)
            return visitUnaryExpr((UnaryExpr) expr);
        if (expr instanceof CallExpr)
            return visitCallExpr((CallExpr) expr);
        if (expr instanceof LiteralExpr)
            return visitLiteralExpr((LiteralExpr) expr);
        if (expr instanceof AccessExpr)
            return visitAccessExpr((AccessExpr) expr);
        if (expr instanceof IndexExpr)
            return visitIndexExpr((IndexExpr) expr);
        if (expr instanceof IfExpr)
            return visitIfExpr((IfExpr) expr);
        if (expr instanceof ElvisExpr)
            return visitElvisExpr((ElvisExpr) expr);
        if (expr instanceof CastExpr)
            return visitCastExpr((CastExpr) expr);
        if (expr instanceof RangeExpr)
            return visitRangeExpr((RangeExpr) expr);
        if (expr instanceof ArrayExpr)
            return visitArrayExpr((ArrayExpr) expr);
        if (expr instanceof LambdaExpr)
            return visitLambdaExpr((LambdaExpr) expr);
        if (expr instanceof TypeCheckExpr)
            return visitTypeCheckExpr((TypeCheckExpr) expr);
        if (expr instanceof MatchExpr)
            return visitMatchExpr((MatchExpr) expr);
        if (expr instanceof AwaitExpr)
            return visitAwaitExpr((AwaitExpr) expr);
        if (expr instanceof ThreadExpr)
            return visitThreadExpr((ThreadExpr) expr);
        return null;
    }

    @Override
    public Void visitDeclaration(Declaration decl) {
        if (decl instanceof FunctionDecl)
            return visitFunctionDecl((FunctionDecl) decl);
        if (decl instanceof ClassDecl)
            return visitClassDecl((ClassDecl) decl);
        if (decl instanceof FieldDecl)
            return visitFieldDecl((FieldDecl) decl);
        if (decl instanceof ParamDecl)
            return visitParamDecl((ParamDecl) decl);
        if (decl instanceof ConstructorDecl)
            return visitConstructorDecl((ConstructorDecl) decl);
        if (decl instanceof EnumDecl)
            return visitEnumDecl((EnumDecl) decl);
        if (decl instanceof InterfaceDecl)
            return visitInterfaceDecl((InterfaceDecl) decl);
        if (decl instanceof RecordDecl)
            return visitRecordDecl((RecordDecl) decl);
        if (decl instanceof ThreadDecl)
            return visitThreadDecl((ThreadDecl) decl);
        return null;
    }

    // -- Statements --

    @Override
    public Void visitBlockStmt(BlockStmt stmt) {
        check(stmt.statements);
        return null;
    }

    @Override
    public Void visitExprStmt(ExprStmt stmt) {
        check(stmt.expr);
        return null;
    }

    @Override
    public Void visitIfStmt(IfStmt stmt) {
        check(stmt.condition);
        check(stmt.thenBlock);
        if (stmt.elseIfs != null) {
            for (var ei : stmt.elseIfs) {
                if (ei != null) {
                    check(ei.condition);
                    check(ei.block);
                }
            }
        }
        check(stmt.elseBlock);
        return null;
    }

    @Override
    public Void visitLoopStmt(LoopStmt stmt) {
        if (stmt instanceof LoopStmt.WhileStmt) {
            check(((LoopStmt.WhileStmt) stmt).condition);
            check(stmt.body);
        } else if (stmt instanceof LoopStmt.ForStmt) {
            check(((LoopStmt.ForStmt) stmt).iterable);
            check(stmt.body);
        } else if (stmt instanceof LoopStmt.RepeatUntilStmt) {
            check(((LoopStmt.RepeatUntilStmt) stmt).condition);
            check(stmt.body);
        }
        return null;
    }

    @Override
    public Void visitTryStmt(TryStmt stmt) {
        check(stmt.tryBlock);
        if (stmt.catches != null) {
            for (TryStmt.CatchClause c : stmt.catches) {
                if (c != null)
                    check(c.block());
            }
        }
        check(stmt.finallyBlock);
        return null;
    }

    @Override
    public Void visitLabeledStmt(LabeledStmt stmt) {
        check(stmt.statement);
        return null;
    }

    @Override
    public Void visitControlStmt(ControlStmt stmt) {
        if (stmt instanceof ControlStmt.ReturnStmt)
            return visitReturnStmt((ControlStmt.ReturnStmt) stmt);
        if (stmt instanceof ControlStmt.ThrowStmt)
            return visitThrowStmt((ControlStmt.ThrowStmt) stmt);
        if (stmt instanceof ControlStmt.BreakStmt)
            return visitBreakStmt((ControlStmt.BreakStmt) stmt);
        if (stmt instanceof ControlStmt.ContinueStmt)
            return visitContinueStmt((ControlStmt.ContinueStmt) stmt);
        return null;
    }

    @Override
    public Void visitReturnStmt(ControlStmt.ReturnStmt stmt) {
        check(stmt.value);
        return null;
    }

    @Override
    public Void visitThrowStmt(ControlStmt.ThrowStmt stmt) {
        check(stmt.value);
        return null;
    }

    @Override
    public Void visitBreakStmt(ControlStmt.BreakStmt stmt) {
        return null;
    }

    @Override
    public Void visitContinueStmt(ControlStmt.ContinueStmt stmt) {
        return null;
    }

    @Override
    public Void visitInitializerBlock(InitializerBlock stmt) {
        check(stmt.body);
        return null;
    }

    // -- Declarations --

    @Override
    public Void visitFunctionDecl(FunctionDecl decl) {
        check(decl.params);
        // Note: do NOT check(decl.returnType) — the TypeNode loc incorrectly spans the
        // entire function body, causing TypeNode to steal the foundPath leaf from
        // FunctionDecl.
        check(decl.body);
        return null;
    }

    @Override
    public Void visitClassDecl(ClassDecl decl) {
        // Visit superclass and trait type references so hovering on them works
        if (decl.superClass != null)
            checkTypeNode(decl.superClass);
        if (decl.interfaces != null)
            decl.interfaces.forEach(this::checkTypeNode);
        check(decl.members);
        return null;
    }

    @Override
    public Void visitFieldDecl(FieldDecl decl) {
        check(decl.init);
        return null;
    }

    @Override
    public Void visitParamDecl(ParamDecl decl) {
        // leaf — foundPath already updated in visit()
        return null;
    }

    @Override
    public Void visitConstructorDecl(ConstructorDecl decl) {
        check(decl.params);
        check(decl.body);
        return null;
    }

    @Override
    public Void visitEnumDecl(EnumDecl decl) {
        check(decl.members);
        return null;
    }

    @Override
    public Void visitEnumMember(EnumDecl.EnumMember member) {
        return null;
    }

    @Override
    public Void visitInterfaceDecl(InterfaceDecl decl) {
        if (decl.members != null)
            check(decl.members);
        return null;
    }

    @Override
    public Void visitRecordDecl(RecordDecl decl) {
        check(decl.members);
        return null;
    }

    @Override
    public Void visitThreadDecl(ThreadDecl decl) {
        check(decl.body);
        return null;
    }

    @Override
    public Void visitClassParamDecl(ClassParamDecl decl) {
        return null;
    }

    @Override
    public Void visitMethodSignature(MethodSignature sig) {
        return null;
    }

    // -- Expressions --

    @Override
    public Void visitBinaryExpr(BinaryExpr expr) {
        check(expr.left);
        check(expr.right);
        return null;
    }

    @Override
    public Void visitUnaryExpr(UnaryExpr expr) {
        check(expr.operand);
        return null;
    }

    @Override
    public Void visitUnaryExprGeneric(UnaryExpr expr) {
        return visitUnaryExpr(expr);
    }

    @Override
    public Void visitCallExpr(CallExpr expr) {
        check(expr.callee);
        if (expr.arguments != null) {
            for (var arg : expr.arguments) {
                check(arg.value);
            }
        }
        return null;
    }

    @Override
    public Void visitAccessExpr(AccessExpr expr) {
        check(expr.object);
        return null;
    }

    @Override
    public Void visitLiteralExpr(LiteralExpr expr) {
        return null;
    }

    @Override
    public Void visitIndexExpr(IndexExpr expr) {
        check(expr.object);
        check(expr.index);
        return null;
    }

    @Override
    public Void visitIfExpr(IfExpr expr) {
        check(expr.condition);
        check(expr.thenBlock);
        if (expr.elseIfs != null) {
            for (var ei : expr.elseIfs) {
                if (ei != null) {
                    check(ei.condition);
                    check(ei.block);
                }
            }
        }
        check(expr.elseBlock);
        return null;
    }

    @Override
    public Void visitElvisExpr(ElvisExpr expr) {
        check(expr.left);
        check(expr.right);
        return null;
    }

    @Override
    public Void visitCastExpr(CastExpr expr) {
        check(expr.expr);
        if (expr.type != null)
            checkTypeNode(expr.type);
        return null;
    }

    @Override
    public Void visitRangeExpr(RangeExpr expr) {
        check(expr.start);
        check(expr.end);
        return null;
    }

    @Override
    public Void visitArrayExpr(ArrayExpr expr) {
        check(expr.elements);
        return null;
    }

    @Override
    public Void visitLambdaExpr(LambdaExpr expr) {
        check(expr.params);
        check(expr.body);
        return null;
    }

    @Override
    public Void visitTypeCheckExpr(TypeCheckExpr expr) {
        check(expr.check);
        if (expr.type != null)
            checkTypeNode(expr.type);
        return null;
    }

    @Override
    public Void visitMatchExpr(MatchExpr expr) {
        check(expr.expression);
        return null;
    }

    @Override
    public Void visitAwaitExpr(AwaitExpr expr) {
        check(expr.expr);
        return null;
    }

    @Override
    public Void visitThreadExpr(ThreadExpr expr) {
        check(expr.body);
        return null;
    }

    @Override
    public Void visitSelfExpr(SelfExpr expr) {
        return null;
    }

    @Override
    public Void visitSuperExpr(SuperExpr expr) {
        return null;
    }

    // -- Type --

    @Override
    public Void visitTypeNode(TypeNode type) {
        return null;
    }

    @Override
    public Void visitTypeNodeLambda(TypeNode.Lambda lambda) {
        return null;
    }

    // -- Other stubs --

    @Override
    public Void visitErrorNode(ErrorNode err) {
        return null;
    }

    @Override
    public Void visitIfStmtElseIf(IfStmt.ElseIf elseif) {
        return null;
    }

    @Override
    public Void visitWhileStmt(LoopStmt.WhileStmt stmt) {
        return null;
    }

    @Override
    public Void visitRepeatUntilStmt(LoopStmt.RepeatUntilStmt stmt) {
        return null;
    }

    @Override
    public Void visitForStmt(LoopStmt.ForStmt stmt) {
        return null;
    }

    @Override
    public Void visitTryStmtCatch(TryStmt.CatchClause catchClause) {
        return null;
    }

    @Override
    public Void visitMatchStmt(MatchStmt stmt) {
        return null;
    }

    @Override
    public Void visitMatchCase(MatchStmt.MatchCase matchCase) {
        return null;
    }

    @Override
    public Void visitMatchPattern(MatchStmt.Pattern pattern) {
        return null;
    }

    @Override
    public Void visitLiteralPattern(MatchStmt.LiteralPattern pattern) {
        return null;
    }

    @Override
    public Void visitRangePattern(MatchStmt.RangePattern pattern) {
        return null;
    }

    @Override
    public Void visitIsPattern(MatchStmt.IsPattern pattern) {
        return null;
    }

    @Override
    public Void visitIdentifierPattern(MatchStmt.IdentifierPattern pattern) {
        return null;
    }

    @Override
    public Void visitDestructurePattern(MatchStmt.DestructurePattern pattern) {
        return null;
    }

    @Override
    public Void visitDefaultPattern(MatchStmt.DefaultPattern pattern) {
        return null;
    }
}