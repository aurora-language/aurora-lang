package aurora.analyzer;

import aurora.parser.tree.*;
import aurora.parser.tree.decls.*;
import aurora.parser.tree.expr.*;
import aurora.parser.tree.stmt.*;
import aurora.parser.tree.util.BinaryOperator;
import aurora.parser.tree.expr.LiteralExpr.LiteralType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static aurora.analyzer.TypeInferenceEngine.numericRank;

/**
 * A static analysis pass that evaluates type safety, focusing on nullability and basic inheritance.
 * This class implements a {@link NodeVisitor} that traverses the AST and collects type information,
 * reporting errors as {@link AuroraDiagnostic} objects for use in the CLI or LSP.
 */
public class TypeChecker implements NodeVisitor<TypeNode> {
    /** A list of diagnostics (errors and warnings) found during analysis. */
    private final List<AuroraDiagnostic> diagnostics = new ArrayList<>();

    /** A stack of symbol tables representing nested scopes. */
    private final List<Map<String, TypeNode>> scopes = new ArrayList<>();

    /** A map of global names to their declarations. */
    private final Map<String, Declaration> globals = new HashMap<>();

    /** The module resolver used to lookup types in other files. */
    private final ModuleResolver modules;

    /** The program AST root currently being checked. */
    private final Program currentProgram;

    /** Built-in "Any" type. */
    private final TypeNode ANY;

    /** Built-in "none" (null) type. */
    private final TypeNode NONE;

    /**
     * Initializes a new TypeChecker for the specified program.
     *
     * @param currentProgram The AST root to analyze.
     * @param modules        The resolver for module-level lookups.
     */
    public TypeChecker(Program currentProgram, ModuleResolver modules) {
        this.currentProgram = currentProgram;
        this.modules = modules;
        this.ANY = new TypeNode(new aurora.parser.SourceLocation(), "Any");
        this.NONE = new TypeNode(new aurora.parser.SourceLocation(), "none");

        if (modules != null) {
            globals.putAll(modules.loadImplicitImports());
        }
    }

    /**
     * Returns the list of diagnostics collected during analysis.
     *
     * @return A list of {@link AuroraDiagnostic} objects.
     */
    public List<AuroraDiagnostic> getDiagnostics() {
        return diagnostics;
    }

    /**
     * Reports a type error at the specified node's location.
     *
     * @param node    The AST node where the error occurred.
     * @param message The error message.
     */
    public void reportError(Node node, String message) {
        diagnostics.add(AuroraDiagnostic.error(node.loc, message, "Aurora TypeChecker"));
    }


    /**
     * Reports a type warning at the specified node's location.
     *
     * @param node    The AST node where the error occurred.
     * @param message The error message.
     */
    public void reportWarn(Node node, String message) {
        diagnostics.add(AuroraDiagnostic.warning(node.loc, message, "Aurora TypeChecker"));
    }

    public Map<String, Declaration> getGlobals() {
        return globals;
    }

    /**
     * Pushes a new symbolic scope onto the stack.
     */
    private void beginScope() {
        scopes.add(new HashMap<>());
    }

    /**
     * Pops the current symbolic scope from the stack.
     */
    private void endScope() {
        if (!scopes.isEmpty()) {
            scopes.removeLast();
        }
    }

    /**
     * Declares a variable in the current scope with the specified type.
     *
     * @param name The name of the variable.
     * @param type The type of the variable.
     */
    private void declareVariable(String name, TypeNode type) {
        if (!scopes.isEmpty()) {
            scopes.getLast().put(name, type != null ? type : ANY);
        }
    }

    /**
     * Resolves the type of a variable by searching through nested scopes.
     *
     * @param name The name of the variable to resolve.
     * @return The variable's type, or {@link #ANY} if not found.
     */
    private TypeNode resolveVariable(String name) {
        for (int i = scopes.size() - 1; i >= 0; i--) {
            if (scopes.get(i).containsKey(name)) {
                return scopes.get(i).get(name);
            }
        }
        return ANY;
    }

    /**
     * Looks up a member (field or method) by name within a class or record declaration,
     * including inherited members from superclasses.
     *
     * @param typeName The name of the type to search in.
     * @return The member's declaration, or {@code null} if not found.
     */
    private Declaration resolveMember(String typeName, String memberName) {
        Node typeDecl = SymbolResolver.resolveTypeName(currentProgram, typeName, modules);
        return resolveMemberInNode(typeDecl, memberName);
    }

    /**
     * Recursively searches for a member in a type declaration node,
     * walking up the superclass chain.
     */
    private Declaration resolveMemberInNode(Node typeDecl, String memberName) {
        if (typeDecl instanceof ClassDecl cls) {
            // Search members
            if (cls.members != null) {
                for (Declaration member : cls.members) {
                    if (memberName.equals(member.name)) return member;
                }
            }
            // Walk superclass chain
            if (cls.superClass != null) {
                Declaration found = resolveMember(cls.superClass.name, memberName);
                if (found != null) return found;
            }
        } else if (typeDecl instanceof RecordDecl rec) {
            if (rec.members != null) {
                for (Declaration member : rec.members) {
                    if (memberName.equals(member.name)) return member;
                }
            }
        }
        return null;
    }

    /**
     * Returns the declared type of a member declaration (field or function return type).
     */
    private TypeNode typeOfMember(Declaration member) {
        if (member instanceof FieldDecl field) return field.type != null ? field.type : ANY;
        if (member instanceof FunctionDecl func) return func.returnType != null ? func.returnType : ANY;
        return ANY;
    }

    /**
     * Determines if a value of one type can be assigned to a variable of another type.
     * Considers "Any", "none" (nullability), and inheritance.
     *
     * @param target The target type.
     * @param value  The value type being assigned.
     * @return {@code true} if the assignment is valid.
     */
    private boolean isAssignable(TypeNode target, TypeNode value) {
        if (target == null || value == null)
            return true;

        // Everything is assignable if Any is involved in this simplistic checker
        if (target.name.equals("Any") || value.name.equals("Any"))
            return true;

        // Nullability check: if value is "none", target must be nullable or "none"
        if (value.name.equals("none")) {
            boolean isTargetNullable = target.suffixes.stream()
                    .anyMatch(s -> s instanceof TypeNode.TypeSuffix.Nullable);
            return isTargetNullable || target.name.equals("none");
        }

        // For simplicity, we just check names and nullability
        if (target.name.equals(value.name) || target.name.equals("object")) {
            return true;
        }

        // Implicit numeric widening:
        //   int   -> long, float, double
        //   long  -> float, double
        //   float -> double
        int targetRank = numericRank(target.name);
        int valueRank  = numericRank(value.name);
        if (targetRank >= 0 && valueRank >= 0) {
            return valueRank < targetRank;
        }

        // Check inheritance
        Node valDecl = SymbolResolver.resolveTypeName(currentProgram, value.name, modules);
        return inherits(valDecl, target.name);
    }

    /**
     * Determines if a type can be cast or type-checked against another type.
     * Unlike isAssignable, this is bi-directional to allow downcasting or checking sub-types.
     *
     * @param left  The actual type of the expression.
     * @param right The target type to check against.
     * @return {@code true} if there's a possibility that left is or can become right.
     */
    private boolean isCastable(TypeNode left, TypeNode right) {
        if (left == null || right == null) return true;

        // Either side being "Any" allows the check
        if (left.name.equals("Any") || right.name.equals("Any")) return true;

        // 1. Upcasting: Is left a subtype of right? (e.g., String is Object)
        if (isAssignable(right, left)) return true;

        // 2. Downcasting: Is right a subtype of left? (e.g., Object could be String)
        if (isAssignable(left, right)) return true;

        // 3. Numeric conversions (e.g., int to double or vice-versa)
        if (numericRank(left.name) >= 0 && numericRank(right.name) >= 0) {
            return true;
        }

        // Optional: Interface check.
        // In Java, you can almost always check a non-final class against an interface.
        Node leftDecl = SymbolResolver.resolveTypeName(currentProgram, left.name, modules);
        Node rightDecl = SymbolResolver.resolveTypeName(currentProgram, right.name, modules);

        if (leftDecl instanceof InterfaceDecl || rightDecl instanceof InterfaceDecl) {
            // If one is an interface, it's potentially castable unless the other is a final class
            // that doesn't implement it. For simplicity, we'll allow it.
            return true;
        }

        return false;
    }

    /**
     * Recursively checks if a declaration inherits from a target type name.
     *
     * @param decl       The declaration to check (Class, Record, or Interface).
     * @param targetName The name of the potential parent type.
     * @return {@code true} if the declaration inherits from the target.
     */
    private boolean inherits(Node decl, String targetName) {
        switch (decl) {
            case null -> {
                return false;
            }
            case ClassDecl cls -> {
                if (cls.name.equals(targetName))
                    return true;
                if (cls.superClass != null
                        && inherits(SymbolResolver.resolveTypeName(currentProgram, cls.superClass.name, modules),
                        targetName))
                    return true;
                if (cls.interfaces != null) {
                    for (TypeNode iface : cls.interfaces) {
                        if (inherits(SymbolResolver.resolveTypeName(currentProgram, iface.name, modules),
                                targetName))
                            return true;
                    }
                }
            }
            case RecordDecl rec -> {
                if (rec.name.equals(targetName))
                    return true;
                if (rec.implementsInterfaces != null) {
                    for (TypeNode iface : rec.implementsInterfaces) {
                        if (inherits(SymbolResolver.resolveTypeName(currentProgram, iface.name, modules),
                                targetName))
                            return true;
                    }
                }
            }
            case InterfaceDecl iface -> {
                if (iface.name.equals(targetName))
                    return true;
                if (iface.interfaces != null) {
                    for (TypeNode parent : iface.interfaces) {
                        if (inherits(SymbolResolver.resolveTypeName(currentProgram, parent.name, modules),
                                targetName))
                            return true;
                    }
                }
            }
            default -> {
            }
        }

        return false;
    }

    public void dumpState(TypeChecker typeChecker, TypeInferenceEngine inferenceEngine, Program program) {
        System.out.println("=== Aurora Compiler State Dump ===");

        System.out.println("\n[Global Symbols]");
        typeChecker.getGlobals().forEach((name, decl) -> {
            String type = (decl instanceof FunctionDecl f) ? "Function" :
                    (decl instanceof ClassDecl) ? "Class" : "Declaration";
            System.out.printf("  %-15s : %s\n", name, type);
        });

        System.out.println("\n[Inferred Return Types]");
        inferenceEngine.getInferredReturnTypes().forEach((func, type) -> {
            System.out.printf("  %-15s -> %s\n", func.name, type);
        });

        System.out.println("\n[Imported Modules]");
        if (program.imports != null) {
            for (Program.Import imp : program.imports) {
                System.out.println("  use " + imp.path);
            }
        }
        System.out.println("\n==================================");
    }

    // --- VISITOR METHODS ---
    @Override
    public TypeNode visitNode(Node node) {
        return ANY;
    }

    @Override
    public TypeNode visitProgram(Program program) {
        // Collect globals
        for (Statement stmt : program.statements) {
            if (stmt instanceof Declaration decl) {
                globals.put(decl.name, decl);
            }
        }

        for (Statement stmt : program.statements) {
            visitStatement(stmt);
        }

        return ANY;
    }

    @Override
    public TypeNode visitProgramPackage(Program.Package pkg) {
        return ANY;
    }

    @Override
    public TypeNode visitProgramImport(Program.Import imp) {
        return ANY;
    }

    @Override
    public TypeNode visitProgramImportWildCard(Program.ImportWildCard imp) {
        return ANY;
    }

    @Override
    public TypeNode visitProgramImportAlias(Program.ImportAlias imp) {
        return ANY;
    }

    @Override
    public TypeNode visitProgramImportMulti(Program.ImportMulti imp) {
        return ANY;
    }

    @Override
    public TypeNode visitTypeNode(TypeNode type) {
        return type;
    }

    @Override
    public TypeNode visitTypeNodeLambda(TypeNode.Lambda lambda) {
        return lambda;
    }

    @Override
    public TypeNode visitStatement(Statement stmt) {
        if (stmt instanceof ExprStmt s)
            return visitExprStmt(s);
        if (stmt instanceof BlockStmt s)
            return visitBlockStmt(s);
        if (stmt instanceof IfStmt s)
            return visitIfStmt(s);
        if (stmt instanceof FieldDecl s)
            return visitFieldDecl(s);
        if (stmt instanceof FunctionDecl s)
            return visitFunctionDecl(s);
        if (stmt instanceof ClassDecl s)
            return visitClassDecl(s);
        if (stmt instanceof EnumDecl s)
            return visitEnumDecl(s);
        if (stmt instanceof RecordDecl s)
            return visitRecordDecl(s);
        if (stmt instanceof InterfaceDecl s)
            return visitInterfaceDecl(s);
        if (stmt instanceof LoopStmt s)
            return visitLoopStmt(s);
        if (stmt instanceof ControlStmt s)
            return visitControlStmt(s);
        return ANY;
    }

    @Override
    public TypeNode visitBlockStmt(BlockStmt stmt) {
        beginScope();
        for (Statement s : stmt.statements) {
            visitStatement(s);
        }
        endScope();
        return ANY;
    }

    @Override
    public TypeNode visitExprStmt(ExprStmt stmt) {
        if (stmt.expr != null)
            visitExpr(stmt.expr);
        return ANY;
    }

    @Override
    public TypeNode visitIfStmt(IfStmt stmt) {
        visitExpr(stmt.condition);
        visitBlockStmt(stmt.thenBlock);
        if (stmt.elseIfs != null) {
            for (IfStmt.ElseIf e : stmt.elseIfs)
                visitIfStmtElseIf(e);
        }
        if (stmt.elseBlock != null)
            visitBlockStmt(stmt.elseBlock);
        return ANY;
    }

    @Override
    public TypeNode visitIfStmtElseIf(IfStmt.ElseIf elseif) {
        visitExpr(elseif.condition);
        visitBlockStmt(elseif.block);
        return ANY;
    }

    @Override
    public TypeNode visitLoopStmt(LoopStmt stmt) {
        if (stmt instanceof LoopStmt.WhileStmt s)
            return visitWhileStmt(s);
        if (stmt instanceof LoopStmt.RepeatUntilStmt s)
            return visitRepeatUntilStmt(s);
        if (stmt instanceof LoopStmt.ForStmt s)
            return visitForStmt(s);
        return ANY;
    }

    @Override
    public TypeNode visitWhileStmt(LoopStmt.WhileStmt stmt) {
        visitExpr(stmt.condition);
        visitBlockStmt(stmt.body);
        return ANY;
    }

    @Override
    public TypeNode visitRepeatUntilStmt(LoopStmt.RepeatUntilStmt stmt) {
        visitBlockStmt(stmt.body);
        visitExpr(stmt.condition);
        return ANY;
    }

    @Override
    public TypeNode visitForStmt(LoopStmt.ForStmt stmt) {
        beginScope();
        TypeNode elemType = inferIterableElementType(stmt.iterable);
        declareVariable(stmt.varName, elemType);
        visitExpr(stmt.iterable);
        visitBlockStmt(stmt.body);
        endScope();
        return ANY;
    }

    private TypeNode inferIterableElementType(Expr expr) {
        if (expr instanceof RangeExpr r) {
            return new TypeNode(r.loc, "int");
        }

        TypeNode t = visitExpr(expr);
        if (t == null || t == ANY) return ANY;

        if (t.suffixes != null && !t.suffixes.isEmpty()) {
            List<TypeNode.TypeSuffix> rest = t.suffixes.subList(0, t.suffixes.size() - 1);
            return new TypeNode(t.loc, t.name, t.typeArguments, rest);
        }

        Node typeDecl = SymbolResolver.resolveTypeName(currentProgram, t.name, modules);
        if (typeDecl instanceof ClassDecl || typeDecl instanceof InterfaceDecl) {
            if (t.typeArguments != null && !t.typeArguments.isEmpty()) {
                return t.typeArguments.getFirst();
            }
        }

        return ANY;
    }

    @Override
    public TypeNode visitTryStmt(TryStmt stmt) {
        visitBlockStmt(stmt.tryBlock);
        for (TryStmt.CatchClause c : stmt.catches)
            visitTryStmtCatch(c);
        if (stmt.finallyBlock != null)
            visitBlockStmt(stmt.finallyBlock);
        return ANY;
    }

    @Override
    public TypeNode visitTryStmtCatch(TryStmt.CatchClause catchClause) {
        beginScope();
        declareVariable(catchClause.var(), catchClause.type());
        visitBlockStmt(catchClause.block());
        endScope();
        return ANY;
    }

    @Override
    public TypeNode visitMatchStmt(MatchStmt stmt) {
        return ANY;
    }

    @Override
    public TypeNode visitMatchCase(MatchStmt.MatchCase matchCase) {
        return ANY;
    }

    @Override
    public TypeNode visitMatchPattern(MatchStmt.Pattern pattern) {
        return ANY;
    }

    @Override
    public TypeNode visitLiteralPattern(MatchStmt.LiteralPattern pattern) {
        return ANY;
    }

    @Override
    public TypeNode visitRangePattern(MatchStmt.RangePattern pattern) {
        return ANY;
    }

    @Override
    public TypeNode visitIsPattern(MatchStmt.IsPattern pattern) {
        return ANY;
    }

    @Override
    public TypeNode visitIdentifierPattern(MatchStmt.IdentifierPattern pattern) {
        return ANY;
    }

    @Override
    public TypeNode visitDestructurePattern(MatchStmt.DestructurePattern pattern) {
        return ANY;
    }

    @Override
    public TypeNode visitDefaultPattern(MatchStmt.DefaultPattern pattern) {
        return ANY;
    }

    @Override
    public TypeNode visitLabeledStmt(LabeledStmt stmt) {
        return ANY;
    }

    @Override
    public TypeNode visitInitializerBlock(InitializerBlock stmt) {
        return ANY;
    }

    @Override
    public TypeNode visitControlStmt(ControlStmt stmt) {
        return ANY;
    }

    @Override
    public TypeNode visitReturnStmt(ControlStmt.ReturnStmt stmt) {
        if (stmt.value != null)
            visitExpr(stmt.value);
        return ANY;
    }

    @Override
    public TypeNode visitThrowStmt(ControlStmt.ThrowStmt stmt) {
        TypeNode throwable = visitExpr(stmt.value);
        if (inherits(throwable, "")) {

        }
        return ANY;
    }

    @Override
    public TypeNode visitBreakStmt(ControlStmt.BreakStmt stmt) {
        return ANY;
    }

    @Override
    public TypeNode visitContinueStmt(ControlStmt.ContinueStmt stmt) {
        return ANY;
    }

    @Override
    public TypeNode visitDeclaration(Declaration decl) {
        return ANY;
    }

    @Override
    public TypeNode visitErrorNode(ErrorNode err) {
        return ANY;
    }

    @Override
    public TypeNode visitClassDecl(ClassDecl decl) {
        beginScope(); // Class scope
        if (decl.members != null) {
            for (Declaration member : decl.members) {
                if (member instanceof FieldDecl field) {
                    declareVariable(field.name, field.type);
                    if (field.init != null) {
                        TypeNode initType = visitExpr(field.init);
                        if (!isAssignable(field.type, initType)) {
                            reportError(field, "Cannot assign type '" + initType + "' to '" + field.type + "'");
                        }
                    }
                } else if (member instanceof FunctionDecl func) {
                    visitFunctionDecl(func);
                } else if (member instanceof ConstructorDecl cons) {
                    visitConstructorDecl(cons);
                }
            }
        }
        endScope();
        return NONE;
    }

    @Override
    public TypeNode visitFieldDecl(FieldDecl decl) {
        declareVariable(decl.name, decl.type);
        if (decl.init != null) {
            TypeNode initType = visitExpr(decl.init);
            if (!isAssignable(decl.type, initType)) {
                reportError(decl, "Cannot assign type '" + initType + "' to '" + decl.type + "'");
            }
        }
        return NONE;
    }

    @Override
    public TypeNode visitFunctionDecl(FunctionDecl decl) {
        beginScope();
        if (decl.params != null) {
            for (ParamDecl p : decl.params)
                declareVariable(p.name, p.type);
        }

        if (decl.body != null) {
            visitBlockStmt(decl.body);
        }
        endScope();
        return NONE;
    }

    @Override
    public TypeNode visitConstructorDecl(ConstructorDecl decl) {
        beginScope();
        if (decl.params != null) {
            for (ParamDecl p : decl.params)
                declareVariable(p.name, p.type);
        }
        if (decl.body instanceof BlockStmt b) {
            visitBlockStmt(b);
        }
        endScope();
        return NONE;
    }

    @Override
    public TypeNode visitClassParamDecl(ClassParamDecl decl) {
        TypeNode paramType = decl.type != null ? decl.type : ANY;
        if (paramType == ANY) {
            paramType = visitExpr(decl.defaultValue);
        }

        if (decl.defaultValue != null) {
            TypeNode defaultType = visitExpr(decl.defaultValue);

            if (paramType == ANY) {
                paramType = defaultType;
            } else {
                if (!isAssignable(paramType, defaultType)) {
                    reportError(decl.defaultValue,
                            "Default value of type '" + defaultType + "' is not assignable to '" + paramType + "'");
                }
            }
        }

        declareVariable(decl.name, paramType);

        return paramType;
    }

    @Override
    public TypeNode visitEnumDecl(EnumDecl decl) {
        return ANY;
    }

    @Override
    public TypeNode visitEnumMember(EnumDecl.EnumMember member) {
        return ANY;
    }

    @Override
    public TypeNode visitThreadDecl(ThreadDecl decl) {
        return ANY;
    }

    @Override
    public TypeNode visitRecordDecl(RecordDecl decl) {
        beginScope();
        if (decl.members != null) {
            for (Declaration member : decl.members) {
                if (member instanceof FieldDecl field) {
                    declareVariable(field.name, field.type != null ? field.type : ANY);
                    if (field.init != null) {
                        TypeNode initType = visitExpr(field.init);
                        if (field.type != null && !isAssignable(field.type, initType)) {
                            reportError(field, "Cannot assign type '" + initType + "' to '" + field.type + "'");
                        }
                    }
                } else if (member instanceof FunctionDecl func) {
                    visitFunctionDecl(func);
                } else if (member instanceof MethodSignature sig) {
                    visitMethodSignature(sig);
                }
            }
        }
        endScope();
        return ANY;
    }

    @Override
    public TypeNode visitParamDecl(ParamDecl decl) {
        TypeNode paramType = decl.type != null ? decl.type : ANY;
        if (decl.defaultValue != null) {
            TypeNode defaultType = visitExpr(decl.defaultValue);
            if (paramType != ANY && !isAssignable(paramType, defaultType)) {
                reportError(decl.defaultValue,
                        "Default value of type '" + defaultType + "' is not assignable to '" + paramType + "'");
            }
        }
        declareVariable(decl.name, paramType);
        return paramType;
    }

    @Override
    public TypeNode visitMethodSignature(MethodSignature sig) {
        beginScope();
        if (sig.params != null) {
            for (ParamDecl p : sig.params)
                declareVariable(p.name, p.type != null ? p.type : ANY);
        }
        endScope();
        return sig.returnType != null ? sig.returnType : ANY;
    }

    @Override
    public TypeNode visitInterfaceDecl(InterfaceDecl decl) {
        beginScope();
        if (decl.members != null) {
            for (Declaration member : decl.members) {
                if (member instanceof MethodSignature sig)
                    visitMethodSignature(sig);
                else if (member instanceof FunctionDecl func)
                    visitFunctionDecl(func);
            }
        }
        endScope();
        return ANY;
    }

    @Override
    public TypeNode visitExpr(Expr expr) {
        return switch (expr) {
            case null -> ANY;
            case LiteralExpr e -> visitLiteralExpr(e);
            case BinaryExpr e -> visitBinaryExpr(e);
            case UnaryExpr e -> visitUnaryExpr(e);
            case CallExpr e -> visitCallExpr(e);
            case AccessExpr e -> visitAccessExpr(e);
            case IndexExpr e -> visitIndexExpr(e);
            case CastExpr e -> visitCastExpr(e);
            case TypeCheckExpr e -> visitTypeCheckExpr(e);
            case RangeExpr e -> visitRangeExpr(e);
            case IfExpr e -> visitIfExpr(e);
            case ElvisExpr e -> visitElvisExpr(e);
            case LambdaExpr e -> visitLambdaExpr(e);
            case MatchExpr e -> visitMatchExpr(e);
            case SelfExpr e -> visitSelfExpr(e);
            case SuperExpr e -> visitSuperExpr(e);
            case AwaitExpr e -> visitAwaitExpr(e);
            case ArrayExpr e -> visitArrayExpr(e);
            case ThreadExpr e -> visitThreadExpr(e);
            default -> {
                reportWarn(expr, "Unhandled expression type: " + expr.getClass().getSimpleName());
                yield ANY;
            }
        };
    }

    @Override
    public TypeNode visitLiteralExpr(LiteralExpr expr) {
        return switch (expr.type) {
            case LiteralType.INT -> new TypeNode(expr.loc, "int");
            case LiteralType.STRING -> new TypeNode(expr.loc, "string");
            case LiteralType.NULL -> NONE;
            case LiteralType.BOOL -> new TypeNode(expr.loc, "bool");
            case LiteralType.FLOAT -> new TypeNode(expr.loc, "float");
            case LiteralType.DOUBLE -> new TypeNode(expr.loc, "double");
            case LiteralType.LONG -> new TypeNode(expr.loc, "long");
        };
    }

    @Override
    public TypeNode visitBinaryExpr(BinaryExpr expr) {
        TypeNode left = visitExpr(expr.left);
        TypeNode right = visitExpr(expr.right);

        if (expr.op == BinaryOperator.IN) {
            return new TypeNode(expr.loc, "bool");
        }

        if (expr.op == BinaryOperator.ASSIGN) {
            if (!isAssignable(left, right)) {
                reportError(expr, "Cannot assign type '" + right + "' to '" + left + "'");
            }
            return right;
        }
        return left;
    }

    @Override
    public TypeNode visitCallExpr(CallExpr expr) {
        List<TypeNode> argTypes = new ArrayList<>();
        for (CallExpr.Argument arg : expr.arguments) {
            argTypes.add(visitExpr(arg.value));
        }

        if (expr.callee instanceof AccessExpr access && access.object != null) {
            TypeNode receiverType = visitExpr(access.object);
            if (!receiverType.name.equals("Any")) {
                Declaration member = resolveMember(receiverType.name, access.member);
                if (member instanceof FunctionDecl func) {
                    if (func.params != null && func.params.size() != argTypes.size()) {
                        reportError(expr, "Expected " + func.params.size() + " arguments, but found " + argTypes.size());
                    }
                    if (func.params != null) {
                        for (int i = 0; i < Math.min(func.params.size(), argTypes.size()); i++) {
                            if (!isAssignable(func.params.get(i).type, argTypes.get(i))) {
                                reportError(argTypes.get(i), "Argument " + (i + 1) + ": expected '" + func.params.get(i).type + "', but found '" + argTypes.get(i) + "'");
                            }
                        }
                    }
                    return func.returnType != null ? func.returnType : ANY;
                }
            }
            return ANY;
        }

        if (expr.callee instanceof AccessExpr bare && bare.object == null) {
            Declaration mayBeDecl = globals.get(bare.member);

            if (mayBeDecl instanceof ClassDecl || mayBeDecl instanceof RecordDecl) {
                return new TypeNode(expr.loc, bare.member);
            }

            if (mayBeDecl instanceof FunctionDecl fun) {
                if (fun.params != null && fun.params.size() != argTypes.size()) {
                    reportError(expr, "Expected " + fun.params.size() + " arguments, but found " + argTypes.size());
                }
                if (fun.params != null) {
                    for (int i = 0; i < Math.min(fun.params.size(), argTypes.size()); i++) {
                        if (!isAssignable(fun.params.get(i).type, argTypes.get(i))) {
                            reportError(argTypes.get(i), "Argument " + (i + 1) + ": expected '" + fun.params.get(i).type + "', but found '" + argTypes.get(i) + "'");
                        }
                    }
                }
                return fun.returnType != null ? fun.returnType : ANY;
            }
        }

        reportWarn(expr, "Unable to inference");
        return ANY;
    }

    @Override
    public TypeNode visitAccessExpr(AccessExpr expr) {
        if (expr.object == null) {
            TypeNode scopeType = resolveVariable(expr.member);
            if (!scopeType.name.equals("Any")) return scopeType;

            Declaration decl = globals.get(expr.member);
            if (decl instanceof ClassDecl cls) return new TypeNode(expr.loc, cls.name);
            if (decl instanceof FunctionDecl func) return func.returnType != null ? func.returnType : ANY;
            return scopeType;
        }

        if (expr.object instanceof SelfExpr) {
            return resolveVariable(expr.member);
        }

        TypeNode objectType = visitExpr(expr.object);
        if (objectType.name.equals("Any")) return ANY;

        String baseTypeName = objectType.name;

        Declaration member = resolveMember(baseTypeName, expr.member);
        if (member != null) return typeOfMember(member);

        Declaration classDecl = globals.get(baseTypeName);
        if (classDecl instanceof ClassDecl cls && cls.members != null) {
            for (Declaration m : cls.members) {
                if (expr.member.equals(m.name)) return typeOfMember(m);
            }
        }

        reportWarn(expr, "Unable to inference");
        return ANY;
    }

    @Override
    public TypeNode visitUnaryExpr(UnaryExpr expr) {
        return ANY;
    }

    @Override
    public TypeNode visitLambdaExpr(LambdaExpr expr) {
        return ANY;
    }

    @Override
    public TypeNode visitIndexExpr(IndexExpr expr) {
        TypeNode objectType = visitExpr(expr.object);
        visitExpr(expr.index);

        if (objectType.suffixes != null && !objectType.suffixes.isEmpty()) {
            TypeNode elementType = new TypeNode(expr.loc, objectType.name);
            List<TypeNode.TypeSuffix> remaining = objectType.suffixes.subList(0, objectType.suffixes.size() - 1);
            elementType.suffixes.addAll(remaining);
            return elementType;
        }

        reportWarn(expr, "Unable to inference");
        return ANY;
    }

    @Override
    public TypeNode visitIfExpr(IfExpr expr) {
        return ANY;
    }

    @Override
    public TypeNode visitElvisExpr(ElvisExpr expr) {
        return ANY;
    }

    @Override
    public TypeNode visitCastExpr(CastExpr expr) {
        TypeNode valueType = visitExpr(expr.expr);
        TypeNode targetType = expr.type;

        if (!isCastable(valueType, targetType)) {
            reportError(expr, "inconvertible types: cannot cast '" + valueType + "' to '" + targetType + "'");
        }

        return targetType;
    }

    @Override
    public TypeNode visitMatchExpr(MatchExpr expr) {
        return ANY;
    }

    @Override
    public TypeNode visitRangeExpr(RangeExpr expr) {
        return new TypeNode(expr.loc, "Range");
    }

    @Override
    public TypeNode visitTypeCheckExpr(TypeCheckExpr expr) {
        TypeNode left = visitExpr(expr.check);
        TypeNode right = expr.type;
        if (!isCastable(left, right)) {
            reportError(expr, "inconvertible types: cannot check type between '" + left + "' and '" + right + "'");
        }
        return new TypeNode(expr.loc, "bool");
    }

    @Override
    public TypeNode visitThreadExpr(ThreadExpr expr) {
        return ANY;
    }

    @Override
    public TypeNode visitSelfExpr(SelfExpr expr) {
        return ANY;
    }

    @Override
    public TypeNode visitSuperExpr(SuperExpr expr) {
        return ANY;
    }

    @Override
    public TypeNode visitAwaitExpr(AwaitExpr expr) {
        return ANY;
    }

    @Override
    public TypeNode visitArrayExpr(ArrayExpr expr) {
        return ANY;
    }

    @Override
    public TypeNode visitUnaryExprGeneric(UnaryExpr expr) {
        return ANY;
    }
}