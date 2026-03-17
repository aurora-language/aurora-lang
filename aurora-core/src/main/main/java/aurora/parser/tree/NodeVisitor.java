package aurora.parser.tree;

import aurora.parser.tree.decls.*;
import aurora.parser.tree.expr.*;
import aurora.parser.tree.stmt.*;

/**
 * An interface for implementing the Visitor pattern on the Aurora AST.
 * This allows operations like compilation, type checking, and formatting to be
 * implemented externally to the node classes themselves, promoting better
 * separation of concerns and maintainability.
 *
 * @param <R> The return type of the visitor's methods.
 */
public interface NodeVisitor<R> {
    R visitNode(Node node);

    R visitProgram(Program program);
    R visitProgramPackage(Program.Package pkg);
    R visitProgramImport(Program.Import imp);
    R visitProgramImportWildCard(Program.ImportWildCard imp);
    R visitProgramImportAlias(Program.ImportAlias imp);
    R visitProgramImportMulti(Program.ImportMulti imp);

    R visitTypeNode(TypeNode type);
    R visitTypeNodeLambda(TypeNode.Lambda lambda);

    R visitStatement(Statement stmt);
    R visitExpr(Expr expr);
    R visitDeclaration(Declaration decl);
    R visitErrorNode(ErrorNode err);

    // Statements
    R visitBlockStmt(BlockStmt stmt);
    R visitExprStmt(ExprStmt stmt);
    R visitIfStmt(IfStmt stmt);
    R visitIfStmtElseIf(IfStmt.ElseIf elseif);
    R visitLoopStmt(LoopStmt stmt);
    R visitWhileStmt(LoopStmt.WhileStmt stmt);
    R visitRepeatUntilStmt(LoopStmt.RepeatUntilStmt stmt);
    R visitForStmt(LoopStmt.ForStmt stmt);
    R visitTryStmt(TryStmt stmt);
    R visitTryStmtCatch(TryStmt.CatchClause catchClause);
    R visitMatchStmt(MatchStmt stmt);
    R visitMatchCase(MatchStmt.MatchCase matchCase);
    R visitMatchPattern(MatchStmt.Pattern pattern);
    R visitLiteralPattern(MatchStmt.LiteralPattern pattern);
    R visitRangePattern(MatchStmt.RangePattern pattern);
    R visitIsPattern(MatchStmt.IsPattern pattern);
    R visitIdentifierPattern(MatchStmt.IdentifierPattern pattern);
    R visitDestructurePattern(MatchStmt.DestructurePattern pattern);
    R visitDefaultPattern(MatchStmt.DefaultPattern pattern);
    R visitLabeledStmt(LabeledStmt stmt);
    R visitInitializerBlock(InitializerBlock stmt);
    R visitControlStmt(ControlStmt stmt);
    R visitReturnStmt(ControlStmt.ReturnStmt stmt);
    R visitThrowStmt(ControlStmt.ThrowStmt stmt);
    R visitBreakStmt(ControlStmt.BreakStmt stmt);
    R visitContinueStmt(ControlStmt.ContinueStmt stmt);

    // Declarations
    R visitClassDecl(ClassDecl decl);
    R visitClassParamDecl(ClassParamDecl decl);
    R visitFieldDecl(FieldDecl decl);
    R visitEnumDecl(EnumDecl decl);
    R visitEnumMember(EnumDecl.EnumMember member);
    R visitConstructorDecl(ConstructorDecl decl);
    R visitFunctionDecl(FunctionDecl decl);
    R visitThreadDecl(ThreadDecl decl);
    R visitRecordDecl(RecordDecl decl);
    R visitParamDecl(ParamDecl decl);
    R visitMethodSignature(MethodSignature sig);
    R visitInterfaceDecl(InterfaceDecl decl);

    // Expressions
    R visitUnaryExpr(UnaryExpr expr);
    R visitBinaryExpr(BinaryExpr expr);
    R visitLiteralExpr(LiteralExpr expr);
    R visitCallExpr(CallExpr expr);
    R visitLambdaExpr(LambdaExpr expr);
    R visitIndexExpr(IndexExpr expr);
    R visitIfExpr(IfExpr expr);
    R visitElvisExpr(ElvisExpr expr);
    R visitCastExpr(CastExpr expr);
    R visitMatchExpr(MatchExpr expr);
    R visitRangeExpr(RangeExpr expr);
    R visitTypeCheckExpr(TypeCheckExpr expr);
    R visitThreadExpr(ThreadExpr expr);
    R visitSelfExpr(SelfExpr expr);
    R visitSuperExpr(SuperExpr expr);
    R visitAwaitExpr(AwaitExpr expr);
    R visitArrayExpr(ArrayExpr expr);
    R visitAccessExpr(AccessExpr expr);
    R visitUnaryExprGeneric(UnaryExpr expr);
}
