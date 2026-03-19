package aurora.compiler;

import aurora.analyzer.AuroraDiagnostic;
import aurora.analyzer.ModuleResolver;
import aurora.analyzer.TypeChecker;
import aurora.analyzer.TypeInferenceEngine;
import aurora.parser.tree.*;
import aurora.parser.tree.decls.*;
import aurora.parser.tree.expr.*;
import aurora.parser.tree.stmt.*;
import aurora.parser.tree.util.BinaryOperator;
import aurora.parser.tree.util.FunctionModifier;
import aurora.parser.tree.util.Visibility;
import aurora.runtime.Chunk;
import aurora.runtime.OpCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * The primary compiler for the Aurora language.
 * This class implements a {@link NodeVisitor} that traverses the Abstract Syntax Tree (AST)
 * and generates virtual machine bytecode (stored in {@link Chunk} objects).
 * It handles scope management, constant pooling, jump patching, and library loading.
 */
public class Compiler implements NodeVisitor<Void> {
    /** The bytecode chunk being generated. */
    private Chunk chunk;

    /** A list of filesystem paths to search for Aurora libraries. */
    private List<Path> libraryPaths = new ArrayList<>();

    /** A set of library names that have already been loaded to prevent cycles. */
    private Set<String> loadedLibraries = new HashSet<>();

    private Map<String, Declaration> globals = new HashMap<>();

    /**
     * Represents a local variable in the current scope.
     *
     * @param name  The name of the local variable.
     * @param depth The scope depth where the variable was declared.
     */
    private record Local(String name, int depth) {
    }

    /** The stack of local variables in the current compilation context. */
    private List<Local> locals = new ArrayList<>();

    /** The current nesting depth of local scopes (0 is global). */
    private int scopeDepth = 0;

    /** The current package or namespace prefix. */
    private String currentNamespace = "";

    /** The class currently being compiled, if any. */
    private ClassDecl currentClass = null;

    /** Indicates whether the compiler is currently processing an instance method. */
    private boolean inInstanceMethod = false;

    /** The name of the file currently being compiled. */
    private String currentFileName;

    private final ModuleResolver modules;

    /**
     * Context information for loops, used to support {@code break} and {@code continue} statements.
     *
     * @param continueTarget The instruction index where a {@code continue} should jump.
     * @param breakJumps      A list of instruction indices for {@code break} jumps that need patching.
     */
    private record LoopContext(int continueTarget, List<Integer> breakJumps) {
        /**
         * Constructs a new LoopContext with a continue target and an empty break list.
         *
         * @param continueTarget The jump target for continue.
         */
        LoopContext(int continueTarget) {
            this(continueTarget, new ArrayList<>());
        }
    }

    /** The stack of nested loop contexts. */
    private final Deque<LoopContext> loopStack = new ArrayDeque<>();

    /**
     * Initializes a new Compiler with default library search paths.
     */
    public Compiler(ModuleResolver modules) {
        this.libraryPaths.add(Paths.get("aurora/lib"));
        this.libraryPaths.add(Paths.get("."));
        this.modules = modules;
    }

    /**
     * Compiles an Aurora {@link Program} AST into a bytecode {@link Chunk}.
     * This method first performs type checking and then performs the actual compilation.
     *
     * @param program The AST root of the Aurora program.
     * @return A completed bytecode chunk.
     * @throws RuntimeException If a type error is detected during pre-compilation analysis.
     */
    public Chunk compile(Program program) {
        this.currentFileName = program.loc.sourceName();
        this.chunk = new Chunk(this.currentFileName);

        TypeChecker checker = new TypeChecker(program, modules);
        checker.visitProgram(program);
        if (!checker.getDiagnostics().isEmpty()) {
            boolean hasError = checker.getDiagnostics().stream()
                    .anyMatch(d -> d.severity() == AuroraDiagnostic.Severity.ERROR);
            if (hasError) {
                throw new TypeErrorException(checker.getDiagnostics());
            } else {
                for (AuroraDiagnostic d : checker.getDiagnostics()) {
                    System.err.print(AuroraDiagnostic.formatDiagnostic(d, program.loc.sourceName()));
                }
            }
        }
        this.globals = checker.getGlobals();

        visitProgram(program);
        if (chunk.count == 0 || chunk.code[chunk.count - 1] != OpCode.HALT.ordinal()) {
            chunk.write(OpCode.HALT.ordinal(), 0, 0);
        }
        return chunk;
    }

    /**
     * Default visitor implementation for an arbitrary node.
     *
     * @param node The node to visit.
     * @return {@code null}.
     */
    @Override
    public Void visitNode(Node node) {
        return null;
    }

    /**
     * Visits the root of the program, processing package declarations, imports, and statements.
     *
     * @param program The program AST node.
     * @return {@code null}.
     */
    @Override
    public Void visitProgram(Program program) {
        String oldNamespace = this.currentNamespace;
        if (program.aPackage != null) {
            visitProgramPackage(program.aPackage);
        }
        if (program.imports != null) {
            for (Program.Import imp : program.imports) {
                visitProgramImport(imp);
            }
        }
        for (Statement stmt : program.statements) {
            visitStatement(stmt);
        }
        this.currentNamespace = oldNamespace;
        return null;
    }

    /**
     * Dispatches a statement to its specific visitor implementation based on its type.
     *
     * @param stmt The statement AST node.
     * @return {@code null}.
     * @throws UnsupportedOperationException If the statement type is unknown.
     */
    @Override
    public Void visitStatement(Statement stmt) {
        if (stmt instanceof ExprStmt)
            return visitExprStmt((ExprStmt) stmt);
        if (stmt instanceof BlockStmt)
            return visitBlockStmt((BlockStmt) stmt);
        if (stmt instanceof IfStmt)
            return visitIfStmt((IfStmt) stmt);
        if (stmt instanceof FieldDecl)
            return visitFieldDecl((FieldDecl) stmt);
        if (stmt instanceof FunctionDecl)
            return visitFunctionDecl((FunctionDecl) stmt);
        if (stmt instanceof ClassDecl)
            return visitClassDecl((ClassDecl) stmt);
        if (stmt instanceof EnumDecl)
            return visitEnumDecl((EnumDecl) stmt);
        if (stmt instanceof RecordDecl)
            return visitRecordDecl((RecordDecl) stmt);
        if (stmt instanceof InterfaceDecl)
            return visitInterfaceDecl((InterfaceDecl) stmt);
        if (stmt instanceof LoopStmt)
            return visitLoopStmt((LoopStmt) stmt);
        if (stmt instanceof ControlStmt)
            return visitControlStmt((ControlStmt) stmt);
        if (stmt instanceof TryStmt)
            return visitTryStmt((TryStmt) stmt);

        throw new UnsupportedOperationException("Statement type not supported: " + stmt.getClass().getSimpleName());
    }

    // ... (existing methods) ...

    @Override
    public Void visitFieldDecl(FieldDecl decl) {
        if (decl.init != null) {
            visitExpr(decl.init);
        } else {
            int noneIndex = chunk.addConstant(null);
            chunk.write(OpCode.LOAD_CONST.ordinal(), decl.loc.line(), decl.loc.column());
            chunk.write(noneIndex, decl.loc.line(), decl.loc.column());
        }

        if (scopeDepth > 0) {
            addLocal(decl.name);
            int localIdx = resolveLocal(decl.name);
            chunk.write(OpCode.SET_LOCAL.ordinal(), decl.loc.line(), decl.loc.column());
            chunk.write(localIdx, decl.loc.line(), decl.loc.column());
            // In Aurora, val/var declarations are statements and don't leave a value on
            // stack?
            // Usually yes. Compiler should pop it.
            chunk.write(OpCode.POP.ordinal(), decl.loc.line(), decl.loc.column());
        } else {
            String fullName = fqn(decl.name);
            int nameIndex = chunk.addConstant(fullName);
            chunk.write(OpCode.SET_GLOBAL.ordinal(), decl.loc.line(), decl.loc.column());
            chunk.write(nameIndex, decl.loc.line(), decl.loc.column());
            chunk.write(OpCode.POP.ordinal(), decl.loc.line(), decl.loc.column());

            if (!fullName.equals(decl.name)) {
                int simpleNameIndex = chunk.addConstant(decl.name);
                // Load from global fullName and set to simpleName
                chunk.write(OpCode.GET_GLOBAL.ordinal(), decl.loc.line(), decl.loc.column());
                chunk.write(nameIndex, decl.loc.line(), decl.loc.column());
                chunk.write(OpCode.SET_GLOBAL.ordinal(), decl.loc.line(), decl.loc.column());
                chunk.write(simpleNameIndex, decl.loc.line(), decl.loc.column());
                chunk.write(OpCode.POP.ordinal(), decl.loc.line(), decl.loc.column());
            }
        }

        return null;
    }

    @Override
    public Void visitExprStmt(ExprStmt stmt) {
        visitExpr(stmt.expr);
        // Special case: some expressions might not leave value on stack? No, all
        // should.
        chunk.write(OpCode.POP.ordinal(), stmt.loc.line(), stmt.loc.column());
        return null;
    }

    @Override
    public Void visitBlockStmt(BlockStmt stmt) {
        beginScope();
        for (Statement s : stmt.statements) {
            visitStatement(s);
        }
        endScope(stmt.loc.line());
        return null;
    }

    private void beginScope() {
        scopeDepth++;
    }

    private void endScope(int line) {
        scopeDepth--;
        while (!locals.isEmpty() && locals.getLast().depth > scopeDepth) {
            chunk.write(OpCode.POP.ordinal(), line, 0);
            locals.removeLast();
        }
    }

    private void addLocal(String name) {
        locals.add(new Local(name, scopeDepth));
    }

    private int resolveLocal(String name) {
        for (int i = locals.size() - 1; i >= 0; i--) {
            if (locals.get(i).name.equals(name)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public Void visitIfStmt(IfStmt stmt) {
        List<Integer> jumpsToEnd = new ArrayList<>();

        // 1. Main If
        visitExpr(stmt.condition);
        chunk.write(OpCode.JUMP_IF_FALSE.ordinal(), stmt.loc.line(), stmt.loc.column());
        int jumpToNextBranchIndex = chunk.count;
        chunk.write(0, stmt.loc.line(), stmt.loc.column()); // Placeholder

        visitBlockStmt(stmt.thenBlock);

        chunk.write(OpCode.JUMP.ordinal(), stmt.loc.line(), stmt.loc.column());
        jumpsToEnd.add(chunk.count);
        chunk.write(0, stmt.loc.line(), stmt.loc.column()); // Placeholder

        // Patch jumpToNextBranch to here
        chunk.code[jumpToNextBranchIndex] = chunk.count;

        // 2. Else Ifs
        if (stmt.elseIfs != null) {
            for (IfStmt.ElseIf elseIf : stmt.elseIfs) {
                visitExpr(elseIf.condition);
                chunk.write(OpCode.JUMP_IF_FALSE.ordinal(), elseIf.loc.line(), elseIf.loc.column());
                jumpToNextBranchIndex = chunk.count;
                chunk.write(0, elseIf.loc.line(), elseIf.loc.column()); // Placeholder

                visitBlockStmt(elseIf.block);

                chunk.write(OpCode.JUMP.ordinal(), elseIf.loc.line(), elseIf.loc.column());
                jumpsToEnd.add(chunk.count);
                chunk.write(0, elseIf.loc.line(), elseIf.loc.column()); // Placeholder

                // Patch jumpToNextBranch to here
                chunk.code[jumpToNextBranchIndex] = chunk.count;
            }
        }

        // 3. Else
        if (stmt.elseBlock != null) {
            visitBlockStmt(stmt.elseBlock);
        }

        // Patch all jumpsToEnd to here
        int endIndex = chunk.count;
        for (int jumpIndex : jumpsToEnd) {
            chunk.code[jumpIndex] = endIndex;
        }

        return null;
    }

    @Override
    public Void visitExpr(Expr expr) {
        if (expr instanceof LiteralExpr)
            return visitLiteralExpr((LiteralExpr) expr);
        if (expr instanceof BinaryExpr)
            return visitBinaryExpr((BinaryExpr) expr);
        if (expr instanceof CallExpr)
            return visitCallExpr((CallExpr) expr);
        if (expr instanceof AccessExpr)
            return visitAccessExpr((AccessExpr) expr);
        if (expr instanceof IfExpr)
            return visitIfExpr((IfExpr) expr);
        if (expr instanceof LambdaExpr)
            return visitLambdaExpr((LambdaExpr) expr);
        if (expr instanceof ArrayExpr)
            return visitArrayExpr((ArrayExpr) expr);
        if (expr instanceof IndexExpr)
            return visitIndexExpr((IndexExpr) expr);
        if (expr instanceof SelfExpr)
            return visitSelfExpr((SelfExpr) expr);
        if (expr instanceof SuperExpr)
            return visitSuperExpr((SuperExpr) expr);
        if (expr instanceof TypeCheckExpr)
            return visitTypeCheckExpr((TypeCheckExpr) expr);
        if (expr instanceof CastExpr)
            return visitCastExpr((CastExpr) expr);
        if (expr instanceof UnaryExpr)
            return visitUnaryExpr((UnaryExpr) expr);
        if (expr instanceof MatchExpr)
            return visitMatchExpr((MatchExpr) expr);
        if (expr instanceof RangeExpr)
            return visitRangeExpr((RangeExpr) expr);
        if (expr instanceof ElvisExpr)
            return visitElvisExpr((ElvisExpr) expr);
        if (expr instanceof ThreadExpr)
            return visitThreadExpr((ThreadExpr) expr);
        if (expr instanceof AwaitExpr)
            return visitAwaitExpr((AwaitExpr) expr);
        throw new UnsupportedOperationException("Expression type not supported: " + expr.getClass().getSimpleName());
    }

    public Void visitMatchExpr(MatchExpr expr) {
        compileMatch(expr.expression, expr.cases, expr.loc.line(), true);
        return null;
    }

    private void compileMatch(Expr expression, List<MatchStmt.MatchCase> cases, int line, boolean isExpr) {
        visitExpr(expression);

        beginScope();
        String tempName = "$match_" + chunk.count;
        addLocal(tempName);
        int tempIdx = resolveLocal(tempName);
        chunk.write(OpCode.SET_LOCAL.ordinal(), line, 0);
        chunk.write(tempIdx, line, 0);
        chunk.write(OpCode.POP.ordinal(), line, 0);

        List<Integer> jumpsToEnd = new ArrayList<>();

        for (MatchStmt.MatchCase mCase : cases) {
            compilePatternMatch(mCase.pattern, tempIdx, mCase.loc.line());

            if (mCase.guard != null) {
                chunk.write(OpCode.JUMP_IF_FALSE.ordinal(), mCase.loc.line(), mCase.loc.column());
                int jumpIfPatternFalse = chunk.count;
                chunk.write(0, mCase.loc.line(), mCase.loc.column());

                visitExpr(mCase.guard);

                chunk.write(OpCode.JUMP.ordinal(), mCase.loc.line(), mCase.loc.column());
                int jumpGuardEnd = chunk.count;
                chunk.write(0, mCase.loc.line(), mCase.loc.column());

                chunk.code[jumpIfPatternFalse] = chunk.count;
                chunk.write(OpCode.FALSE.ordinal(), mCase.loc.line(), mCase.loc.column());

                chunk.code[jumpGuardEnd] = chunk.count;
            }

            chunk.write(OpCode.JUMP_IF_FALSE.ordinal(), mCase.loc.line(), mCase.loc.column());
            int jumpToNextCase = chunk.count;
            chunk.write(0, mCase.loc.line(), mCase.loc.column());

            beginScope();
            if (mCase.pattern instanceof MatchStmt.IdentifierPattern idPat) {
                chunk.write(OpCode.GET_LOCAL.ordinal(), mCase.loc.line(), mCase.loc.column());
                chunk.write(tempIdx, mCase.loc.line(), mCase.loc.column());
                addLocal(idPat.name);
                int bindIdx = resolveLocal(idPat.name);
                chunk.write(OpCode.SET_LOCAL.ordinal(), mCase.loc.line(), mCase.loc.column());
                chunk.write(bindIdx, mCase.loc.line(), mCase.loc.column());
                chunk.write(OpCode.POP.ordinal(), mCase.loc.line(), mCase.loc.column());
            }

            if (mCase.body instanceof Expr exprBody) {
                visitExpr(exprBody);
                if (!isExpr) {
                    chunk.write(OpCode.POP.ordinal(), mCase.loc.line(), mCase.loc.column());
                }
            } else if (mCase.body instanceof Statement stmtBody) {
                visitStatement(stmtBody);
                if (isExpr) {
                    // MatchExpr should put a value on the stack. If body is a block, this is
                    // tricky.
                    // Assuming body puts a value on stack if it's an expr. For now, push null if
                    // it's a stmt.
                    int noneIndex = chunk.addConstant(null);
                    chunk.write(OpCode.LOAD_CONST.ordinal(), mCase.loc.line(), mCase.loc.column());
                    chunk.write(noneIndex, mCase.loc.line(), mCase.loc.column());
                }
            }

            endScope(mCase.loc.line());

            chunk.write(OpCode.JUMP.ordinal(), mCase.loc.line(), mCase.loc.column());
            jumpsToEnd.add(chunk.count);
            chunk.write(0, mCase.loc.line(), mCase.loc.column());

            chunk.code[jumpToNextCase] = chunk.count;
        }

        // If no case matched and it's an expression, push null
        if (isExpr) {
            int noneIndex = chunk.addConstant(null);
            chunk.write(OpCode.LOAD_CONST.ordinal(), line, 0);
            chunk.write(noneIndex, line, 0);
        }

        for (int jump : jumpsToEnd) {
            chunk.code[jump] = chunk.count;
        }

        endScope(line);
    }

    private void compilePatternMatch(MatchStmt.Pattern pattern, int tempIdx, int line) {
        if (pattern instanceof MatchStmt.DefaultPattern || pattern instanceof MatchStmt.IdentifierPattern) {
            chunk.write(OpCode.TRUE.ordinal(), line, 0);
        } else if (pattern instanceof MatchStmt.LiteralPattern litPat) {
            chunk.write(OpCode.GET_LOCAL.ordinal(), line, 0);
            chunk.write(tempIdx, line, 0);
            visitExpr(litPat.literal);
            chunk.write(OpCode.EQUAL.ordinal(), line, 0);
        } else if (pattern instanceof MatchStmt.MultiPattern multiPat) {
            if (multiPat.patterns.isEmpty()) {
                chunk.write(OpCode.FALSE.ordinal(), line, 0);
                return;
            }
            List<Integer> jumpsToTrue = new ArrayList<>();
            for (int i = 0; i < multiPat.patterns.size(); i++) {
                MatchStmt.Pattern subPat = multiPat.patterns.get(i);
                compilePatternMatch(subPat, tempIdx, line); // Recursively compile

                if (i < multiPat.patterns.size() - 1) {
                    // if subPat matched (true), jump to the end of the OR chain
                    chunk.write(OpCode.JUMP_IF_FALSE.ordinal(), line, 0);
                    int jumpToNext = chunk.count;
                    chunk.write(0, line, 0);

                    chunk.write(OpCode.TRUE.ordinal(), line, 0);
                    chunk.write(OpCode.JUMP.ordinal(), line, 0);
                    jumpsToTrue.add(chunk.count);
                    chunk.write(0, line, 0);

                    chunk.code[jumpToNext] = chunk.count;
                }
            }
            for (int jump : jumpsToTrue) {
                chunk.code[jump] = chunk.count;
            }
        } else if (pattern instanceof MatchStmt.RangePattern rangePat) {
            chunk.write(OpCode.GET_LOCAL.ordinal(), line, 0);
            chunk.write(tempIdx, line, 0);
            visitExpr(rangePat.start);
            chunk.write(OpCode.GREATER_EQUAL.ordinal(), line, 0);

            chunk.write(OpCode.JUMP_IF_FALSE.ordinal(), line, 0);
            int jumpIfFalse = chunk.count;
            chunk.write(0, line, 0);

            chunk.write(OpCode.GET_LOCAL.ordinal(), line, 0);
            chunk.write(tempIdx, line, 0);
            visitExpr(rangePat.end);
            if (rangePat.inclusive) {
                chunk.write(OpCode.LESS_EQUAL.ordinal(), line, 0);
            } else {
                chunk.write(OpCode.LESS.ordinal(), line, 0);
            }

            chunk.write(OpCode.JUMP.ordinal(), line, 0);
            int jumpToEnd = chunk.count;
            chunk.write(0, line, 0);

            chunk.code[jumpIfFalse] = chunk.count;
            chunk.write(OpCode.FALSE.ordinal(), line, 0);

            chunk.code[jumpToEnd] = chunk.count;
        } else if (pattern instanceof MatchStmt.IsPattern isPat) {
            chunk.write(OpCode.GET_LOCAL.ordinal(), line, 0);
            chunk.write(tempIdx, line, 0);
            int nameIndex = chunk.addConstant(isPat.type.name);
            chunk.write(OpCode.GET_GLOBAL.ordinal(), line, 0);
            chunk.write(nameIndex, line, 0);
            chunk.write(OpCode.IS.ordinal(), line, 0);
        } else {
            throw new UnsupportedOperationException("Unsupported pattern: " + pattern.getClass().getSimpleName());
        }
    }

    @Override
    public Void visitUnaryExpr(UnaryExpr expr) {
        visitExpr(expr.operand);
        switch (expr.op) {
            case NOT:
                chunk.write(OpCode.NOT.ordinal(), expr.loc.line(), expr.loc.column());
                break;
            case NEGATE:
                chunk.write(OpCode.NEG.ordinal(), expr.loc.line(), expr.loc.column());
                break;
            case NONNULL:
                // TODO: Implement non-null assertion runtime check
                break;
            case POSITIVE:
                // No-op for unary plus
                break;
            default:
                throw new UnsupportedOperationException("Unary operator not supported: " + expr.op);
        }
        return null;
    }

    @Override
    public Void visitCastExpr(CastExpr expr) {
        visitExpr(expr.expr);

        // Similar to visitTypeCheckExpr, we use the raw type name for lookup
        String name = expr.type.name;

        int nameIndex = chunk.addConstant(name);
        chunk.write(OpCode.GET_GLOBAL.ordinal(), expr.loc.line(), expr.loc.column());
        chunk.write(nameIndex, expr.loc.line(), expr.loc.column());

        chunk.write(OpCode.AS.ordinal(), expr.loc.line(), expr.loc.column());
        return null;
    }

    @Override
    public Void visitTypeCheckExpr(TypeCheckExpr expr) {
        visitExpr(expr.check);
        // Load type to check against
        // We need to resolve the type to an ArClass object (or similar) on the stack

        // We use expr.type.name because that contains the class name (e.g. "Result" or
        // "Aurora.Result")
        // without generic type arguments (e.g. "<T, E>"), which matches the global
        // variable name.
        String name = expr.type.name;

        int nameIndex = chunk.addConstant(name);
        chunk.write(OpCode.GET_GLOBAL.ordinal(), expr.loc.line(), expr.loc.column());
        chunk.write(nameIndex, expr.loc.line(), expr.loc.column());

        chunk.write(OpCode.IS.ordinal(), expr.loc.line(), expr.loc.column());
        return null;
    }

    @Override
    public Void visitLiteralExpr(LiteralExpr expr) {
        int index;
        switch (expr.type) {
            case INT:
                index = chunk.addConstant(((Number) expr.value).intValue());
                break;
            case LONG:
                index = chunk.addConstant(((Number) expr.value).longValue());
                break;
            case FLOAT:
                index = chunk.addConstant(((Number) expr.value).floatValue());
                break;
            case DOUBLE:
                index = chunk.addConstant(((Number) expr.value).doubleValue());
                break;
            case STRING:
                index = chunk.addConstant((String) expr.value);
                break;
            case BOOL:
                if (Boolean.parseBoolean((String) expr.value) || "true".equals(expr.value)) {
                    chunk.write(OpCode.TRUE.ordinal(), expr.loc.line(), expr.loc.column());
                    return null;
                } else {
                    chunk.write(OpCode.FALSE.ordinal(), expr.loc.line(), expr.loc.column());
                    return null;
                }
            case NULL:
                index = chunk.addConstant(null);
                break;
            default:
                throw new UnsupportedOperationException("Literal type not supported: " + expr.type);
        }
        chunk.write(OpCode.LOAD_CONST.ordinal(), expr.loc.line(), expr.loc.column());
        chunk.write(index, expr.loc.line(), expr.loc.column());
        return null;
    }

    @Override
    public Void visitBinaryExpr(BinaryExpr expr) {
        if (expr.op == BinaryOperator.ASSIGN || expr.op == BinaryOperator.PLUS_ASSIGN ||
                expr.op == BinaryOperator.MINUS_ASSIGN || expr.op == BinaryOperator.STAR_ASSIGN ||
                expr.op == BinaryOperator.SLASH_ASSIGN || expr.op == BinaryOperator.PERCENT_ASSIGN) {

            if (expr.left instanceof AccessExpr access) {
                if (access.object == null && isClassMember(currentClass, access.member)
                        && resolveLocal(access.member) == -1) {
                    access = new AccessExpr(access.loc, new SelfExpr(access.loc), access.member, access.isSafe,
                            access.isStatic);
                }
                if (access.object == null) {
                    int localIdx = resolveLocal(access.member);

                    if (expr.op != BinaryOperator.ASSIGN) {
                        // Compound assignment: target = target OP right
                        if (localIdx != -1) {
                            chunk.write(OpCode.GET_LOCAL.ordinal(), expr.loc.line(), expr.loc.column());
                            chunk.write(localIdx, expr.loc.line(), expr.loc.column());
                        } else {
                            int nameIdx = chunk.addConstant(access.member);
                            chunk.write(OpCode.GET_GLOBAL.ordinal(), expr.loc.line(), expr.loc.column());
                            chunk.write(nameIdx, expr.loc.line(), expr.loc.column());
                        }
                        visitExpr(expr.right);
                        int op = switch (expr.op) {
                            case PLUS_ASSIGN -> OpCode.ADD.ordinal();
                            case MINUS_ASSIGN -> OpCode.SUB.ordinal();
                            case STAR_ASSIGN -> OpCode.MUL.ordinal();
                            case SLASH_ASSIGN -> OpCode.DIV.ordinal();
                            case PERCENT_ASSIGN -> OpCode.MOD.ordinal();
                            default -> throw new IllegalStateException("Unexpected assignment operator: " + expr.op);
                        };
                        chunk.write(op, expr.loc.line(), expr.loc.column());
                    } else {
                        visitExpr(expr.right);
                    }

                    if (localIdx != -1) {
                        chunk.write(OpCode.SET_LOCAL.ordinal(), expr.loc.line(), expr.loc.column());
                        chunk.write(localIdx, expr.loc.line(), expr.loc.column());
                    } else {
                        int nameIdx = chunk.addConstant(access.member);
                        chunk.write(OpCode.SET_GLOBAL.ordinal(), expr.loc.line(), expr.loc.column());
                        chunk.write(nameIdx, expr.loc.line(), expr.loc.column());
                    }
                    return null;
                } else {
                    if (access.object instanceof SuperExpr) {
                        if (expr.op != BinaryOperator.ASSIGN) {
                            // Compound on super.property isn't easily supported without dup or temporary
                            throw new UnsupportedOperationException("Compound assignment on super is not supported.");
                        }

                        // Push 'self' for super property access
                        chunk.write(OpCode.GET_LOCAL.ordinal(), expr.loc.line(), expr.loc.column());
                        chunk.write(0, expr.loc.line(), expr.loc.column());

                        visitExpr(expr.right);
                        int nameIdx = chunk.addConstant(access.member);
                        chunk.write(OpCode.SUPER_SET_PROPERTY.ordinal(), expr.loc.line(), expr.loc.column());
                        chunk.write(nameIdx, expr.loc.line(), expr.loc.column());
                        return null;
                    }
                    visitExpr(access.object);
                    if (expr.op != BinaryOperator.ASSIGN) {
                        throw new UnsupportedOperationException("Compound assignment on object property is not fully supported yet.");
                    }
                    visitExpr(expr.right);
                    int nameIdx = chunk.addConstant(access.member);
                    chunk.write(OpCode.SET_PROPERTY.ordinal(), expr.loc.line(), expr.loc.column());
                    chunk.write(nameIdx, expr.loc.line(), expr.loc.column());
                    return null;
                }
            } else if (expr.left instanceof IndexExpr indexExpr) {
                if (expr.op != BinaryOperator.ASSIGN) {
                    throw new UnsupportedOperationException("Compound assignment on array index is not supported.");
                }
                visitExpr(indexExpr.object);
                visitExpr(indexExpr.index);
                visitExpr(expr.right);
                chunk.write(OpCode.SET_INDEX.ordinal(), expr.loc.line(), expr.loc.column());
                return null;
            }
            throw new UnsupportedOperationException("Invalid assignment target.");
        }

        if (expr.op == BinaryOperator.AND) {
            visitExpr(expr.left);
            chunk.write(OpCode.JUMP_IF_FALSE.ordinal(), expr.loc.line(), expr.loc.column());
            int jumpToElse = chunk.count;
            chunk.write(0, expr.loc.line(), expr.loc.column());

            visitExpr(expr.right);
            chunk.write(OpCode.JUMP.ordinal(), expr.loc.line(), expr.loc.column());
            int jumpToEnd = chunk.count;
            chunk.write(0, expr.loc.line(), expr.loc.column());

            // Else: left was false. Push false.
            chunk.code[jumpToElse] = chunk.count;
            chunk.write(OpCode.FALSE.ordinal(), expr.loc.line(), expr.loc.column());

            chunk.code[jumpToEnd] = chunk.count;
            return null;
        }

        if (expr.op == BinaryOperator.OR) {
            visitExpr(expr.left);
            chunk.write(OpCode.JUMP_IF_FALSE.ordinal(), expr.loc.line(), expr.loc.column());
            int jumpToElse = chunk.count;
            chunk.write(0, expr.loc.line(), expr.loc.column());

            // Left was true. Push true.
            chunk.write(OpCode.TRUE.ordinal(), expr.loc.line(), expr.loc.column());
            chunk.write(OpCode.JUMP.ordinal(), expr.loc.line(), expr.loc.column());
            int jumpToEnd = chunk.count;
            chunk.write(0, expr.loc.line(), expr.loc.column());

            // Else: left was false. Check right.
            chunk.code[jumpToElse] = chunk.count;
            visitExpr(expr.right);

            chunk.code[jumpToEnd] = chunk.count;
            return null;
        }

        if (expr.op == BinaryOperator.IN) {
            // `left in right` -> `right.check(left)`
            visitExpr(expr.right);
            visitExpr(expr.left);

            int methodNameIndex = chunk.addConstant("check");
            chunk.write(OpCode.INVOKE.ordinal(), expr.loc.line(), expr.loc.column());
            chunk.write(methodNameIndex, expr.loc.line(), expr.loc.column());
            chunk.write(1, expr.loc.line(), expr.loc.column()); // 1 argument
            return null;
        }

        visitExpr(expr.left);
        visitExpr(expr.right);

        int op = switch (expr.op) {
            case ADD -> OpCode.ADD.ordinal();
            case SUB -> OpCode.SUB.ordinal();
            case MUL -> OpCode.MUL.ordinal();
            case DIV -> OpCode.DIV.ordinal();
            case MOD -> OpCode.MOD.ordinal();
            case EQ -> OpCode.EQUAL.ordinal();
            case NEQ -> OpCode.NOT_EQUAL.ordinal();
            case GT -> OpCode.GREATER.ordinal();
            case GE -> OpCode.GREATER_EQUAL.ordinal();
            case LT -> OpCode.LESS.ordinal();
            case LE -> OpCode.LESS_EQUAL.ordinal();
            default -> throw new UnsupportedOperationException("Binary operator not supported: " + expr.op);
        };
        chunk.write(op, expr.loc.line(), expr.loc.column());
        return null;
    }

    @Override
    public Void visitArrayExpr(ArrayExpr expr) {
        for (Expr element : expr.elements) {
            visitExpr(element);
        }
        chunk.write(OpCode.NEW_ARRAY.ordinal(), expr.loc.line(), expr.loc.column());
        chunk.write(expr.elements.size(), expr.loc.line(), expr.loc.column());
        return null;
    }

    @Override
    public Void visitIndexExpr(IndexExpr expr) {
        visitExpr(expr.object);
        visitExpr(expr.index);
        chunk.write(OpCode.GET_INDEX.ordinal(), expr.loc.line(), expr.loc.column());
        return null;
    }

    @Override
    public Void visitCallExpr(CallExpr expr) {
        if (expr.callee instanceof AccessExpr access && access.object instanceof SuperExpr) {
            // Super call: super.method(args)
            // Push 'self'
            chunk.write(OpCode.GET_LOCAL.ordinal(), expr.loc.line(), expr.loc.column());
            chunk.write(0, expr.loc.line(), expr.loc.column());

            for (CallExpr.Argument arg : expr.arguments) {
                visitExpr(arg.value);
            }

            int nameIndex = chunk.addConstant(access.member);
            chunk.write(OpCode.SUPER_INVOKE.ordinal(), expr.loc.line(), expr.loc.column());
            chunk.write(nameIndex, expr.loc.line(), expr.loc.column());
            chunk.write(expr.arguments.size(), expr.loc.line(), expr.loc.column());
            return null;
        } else if (expr.callee instanceof SuperExpr) {
            // Super constructor call: super(args)
            // Push 'self'
            chunk.write(OpCode.GET_LOCAL.ordinal(), expr.loc.line(), expr.loc.column());
            chunk.write(0, expr.loc.line(), expr.loc.column());

            for (CallExpr.Argument arg : expr.arguments) {
                visitExpr(arg.value);
            }

            int nameIndex = chunk.addConstant("<init>");
            chunk.write(OpCode.SUPER_INVOKE.ordinal(), expr.loc.line(), expr.loc.column());
            chunk.write(nameIndex, expr.loc.line(), expr.loc.column());
            chunk.write(expr.arguments.size(), expr.loc.line(), expr.loc.column());
            return null;
        }

        if (expr.callee instanceof AccessExpr bare && bare.object == null) {
            Declaration decl = globals.get(bare.member);
            String resolvedName = bare.member; // 基本はそのままの名前
            if (decl == null) {
                decl = globals.get(fqn(bare.member));
                if (decl != null) resolvedName = fqn(bare.member); // FQNで見つかった場合はFQNを使う
            }
            if (decl instanceof ClassDecl || decl instanceof RecordDecl) {
                // String fullName = fqn(bare.member); // ← 削除
                int nameIndex = chunk.addConstant(resolvedName); // ← fullName の代わりに resolvedName を使用
                chunk.write(OpCode.GET_GLOBAL.ordinal(), expr.loc.line(), expr.loc.column());
                chunk.write(nameIndex, expr.loc.line(), expr.loc.column());

                for (CallExpr.Argument arg : expr.arguments) {
                    visitExpr(arg.value);
                }

                chunk.write(OpCode.NEW.ordinal(), expr.loc.line(), expr.loc.column());
                chunk.write(expr.arguments.size(), expr.loc.line(), expr.loc.column());
                return null;
            }
        }

        if (expr.callee instanceof AccessExpr access && access.object == null
                && isClassMember(currentClass, access.member) && resolveLocal(access.member) == -1
                && inInstanceMethod) {
            AccessExpr newAccess = new AccessExpr(access.loc, new SelfExpr(access.loc), access.member, access.isSafe,
                    access.isStatic);
            expr = new CallExpr(expr.loc, newAccess, expr.arguments);
        }

        if (expr.callee instanceof AccessExpr access && access.object != null) {

            // Static method call: Class::method(args)
            // CALL expects: callee at bottom, args on top.
            // Push callee via GET_GLOBAL(class) + GET_PROPERTY(method), then args.
            if (access.isStatic) {
                // 1. Push callee: resolve class object, then extract the method
                visitExpr(access.object);
                int methodIdx = chunk.addConstant(access.member);
                chunk.write(OpCode.GET_PROPERTY.ordinal(), expr.loc.line(), expr.loc.column());
                chunk.write(methodIdx, expr.loc.line(), expr.loc.column());
                // 2. Push args
                for (CallExpr.Argument arg : expr.arguments) {
                    visitExpr(arg.value);
                }
                // 3. CALL
                chunk.write(OpCode.CALL.ordinal(), expr.loc.line(), expr.loc.column());
                chunk.write(expr.arguments.size(), expr.loc.line(), expr.loc.column());
                return null;
            }

            // Instance method call: object.method(args)
            // レシーバの評価（インスタンスがスタックに積まれる）
            visitExpr(access.object);

            // 引数の評価
            for (CallExpr.Argument arg : expr.arguments) {
                visitExpr(arg.value);
            }

            int nameIndex = chunk.addConstant(access.member);

            chunk.write(OpCode.INVOKE.ordinal(), expr.loc.line(), expr.loc.column());
            chunk.write(nameIndex, expr.loc.line(), expr.loc.column());
            chunk.write(expr.arguments.size(), expr.loc.line(), expr.loc.column());
            return null;
        }

        visitExpr(expr.callee);
        for (CallExpr.Argument arg : expr.arguments) {
            visitExpr(arg.value);
        }

        chunk.write(OpCode.CALL.ordinal(), expr.loc.line(), expr.loc.column());
        chunk.write(expr.arguments.size(), expr.loc.line(), expr.loc.column());
        return null;
    }

    /**
     * Visits an access expression (member access or variable lookup).
     * Handles both local variable access and property/method access on objects,
     * including 'super' access.
     *
     * @param expr The access expression AST node.
     * @return {@code null}.
     */
    @Override
    public Void visitAccessExpr(AccessExpr expr) {
        if (expr.object == null && isClassMember(currentClass, expr.member)
                && resolveLocal(expr.member) == -1 && inInstanceMethod) {
            expr = new AccessExpr(expr.loc, new SelfExpr(expr.loc), expr.member, expr.isSafe, expr.isStatic);
        }

        String maybeFqn = getFqn(expr);
        if (maybeFqn != null && globals.containsKey(maybeFqn)) {
            Declaration decl = globals.get(maybeFqn);
            // 完全修飾名が既知のクラスや関数であれば、単一のグローバル変数として取得
            if (decl instanceof ClassDecl || decl instanceof RecordDecl || decl instanceof FunctionDecl || decl instanceof FieldDecl) {
                int nameIndex = chunk.addConstant(maybeFqn);
                chunk.write(OpCode.GET_GLOBAL.ordinal(), expr.loc.line(), expr.loc.column());
                chunk.write(nameIndex, expr.loc.line(), expr.loc.column());
                return null;
            }
        }

        if (expr.object == null) {
            // Try current namespace first
            String localFqn = fqn(expr.member);
            // We don't have a symbol table here, so we can't easily check if localFqn
            // exists.
            // But we know Aurora.Io is NOT in current namespace if it starts with Aurora.
            if (expr.member.startsWith("Aurora.") || expr.member.startsWith("std.")) {
                int nameIndex = chunk.addConstant(expr.member);
                chunk.write(OpCode.GET_GLOBAL.ordinal(), expr.loc.line(), expr.loc.column());
                chunk.write(nameIndex, expr.loc.line(), expr.loc.column());
                return null;
            }

            int localIndex = resolveLocal(expr.member);
            if (localIndex != -1) {
                chunk.write(OpCode.GET_LOCAL.ordinal(), expr.loc.line(), expr.loc.column());
                chunk.write(localIndex, expr.loc.line(), expr.loc.column());
            } else {
                // If it's a known global (like Io), use it directly.
                // This is a bit of a hack without a proper symbol table.
                int nameIndex = chunk.addConstant(expr.member);
                chunk.write(OpCode.GET_GLOBAL.ordinal(), expr.loc.line(), expr.loc.column());
                chunk.write(nameIndex, expr.loc.line(), expr.loc.column());
            }
            return null;
        }

        // Member access (expr.object.member)
        if (expr.object instanceof SuperExpr) {
            // Push 'self'
            chunk.write(OpCode.GET_LOCAL.ordinal(), expr.loc.line(), expr.loc.column());
            chunk.write(0, expr.loc.line(), expr.loc.column());

            int nameIndex = chunk.addConstant(expr.member);
            chunk.write(OpCode.SUPER_GET_PROPERTY.ordinal(), expr.loc.line(), expr.loc.column());
            chunk.write(nameIndex, expr.loc.line(), expr.loc.column());
            return null;
        }

        visitExpr(expr.object);
        int nameIndex = chunk.addConstant(expr.member);
        chunk.write(OpCode.GET_PROPERTY.ordinal(), expr.loc.line(), expr.loc.column());
        chunk.write(nameIndex, expr.loc.line(), expr.loc.column());
        return null;
    }

    /**
     * Resolves the fully qualified name (FQN) for an access expression if it represents a namespace.
     *
     * @param access The access expression to resolve.
     * @return The FQN as a string, or {@code null} if it doesn't represent a static namespace.
     */
    private String getFqn(AccessExpr access) {
        if (access.object == null) {
            String base = access.member;
            if (base.equals("Io"))
                base = "Aurora.Io";
            return base;
        } else if (access.object instanceof AccessExpr inner) {
            String parent = getFqn(inner);
            if (parent != null)
                return parent + "." + access.member;
        }
        return null;
    }

    /**
     * Visits an import statement.
     * Marks the library as loaded and emits an IMPORT opcode for the VM.
     *
     * @param imp The import AST node.
     * @return {@code null}.
     */
    @Override
    public Void visitProgramImport(Program.Import imp) {
        String libPath = imp.path;

        // Skip if already emitted an IMPORT for this module
        if (loadedLibraries.contains(libPath))
            return null;
        loadedLibraries.add(libPath);

        // Emit IMPORT opcode — the VM will resolve and load the module at runtime
        int nameIndex = chunk.addConstant(libPath);
        chunk.write(OpCode.IMPORT.ordinal(), imp.loc != null ? imp.loc.line() : 0,
                imp.loc != null ? imp.loc.column() : 0);
        chunk.write(nameIndex, imp.loc != null ? imp.loc.line() : 0,
                imp.loc != null ? imp.loc.column() : 0);

        return null;
    }

    /**
     * Loads and parses a library file from the filesystem.
     *
     * @param file    The path to the library file.
     * @param libPath The logical library path (e.g., "Aurora.Io").
     * @throws RuntimeException If the file cannot be read or parsed.
     */
    private void loadLibraryFile(Path file, String libPath) {
        try {
            String code = Files.readString(file);
            Program libProgram = aurora.parser.AuroraParser.parse(code, file.getFileName().toString(), modules);
            visitProgram(libProgram);
        } catch (aurora.parser.SyntaxErrorException e) {
            throw e; // Let native syntax errors bubble up directly
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to load library: " + libPath, e);
        }
    }

    /**
     * Converts a simple name to a fully qualified name based on the current namespace.
     *
     * @param name The simple name to qualify.
     * @return The fully qualified name.
     */
    private String fqn(String name) {
        if (name.startsWith("Aurora."))
            return name;
        if (name.startsWith("std."))
            return name;
        if (name.contains("."))
            return name; // Already qualified
        if (currentNamespace == null || currentNamespace.isEmpty())
            return name;
        return currentNamespace + "." + name;
    }

    /**
     * Checks if a name corresponds to a member (field or method) of a class.
     *
     * @param cls  The class declaration to check.
     * @param name The name of the member.
     * @return {@code true} if the member exists in the class.
     */
    private boolean isClassMember(ClassDecl cls, String name) {
        if (cls == null)
            return false;
        for (Declaration member : cls.members) {
            if (name.equals(member.name))
                return true;
        }
        return false;
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
    public Void visitTypeNode(TypeNode type) {
        return null;
    }

    @Override
    public Void visitTypeNodeLambda(TypeNode.Lambda lambda) {
        return null;
    }

    @Override
    public Void visitDeclaration(Declaration decl) {
        return null;
    }

    @Override
    public Void visitErrorNode(ErrorNode err) {
        return null;
    }

    @Override
    public Void visitIfStmtElseIf(IfStmt.ElseIf elseif) {
        return null;
    }

    /**
     * Dispatches a loop statement to its specific implementation.
     *
     * @param stmt The loop statement AST node.
     * @return {@code null}.
     * @throws UnsupportedOperationException If the loop type is unknown.
     */
    @Override
    public Void visitLoopStmt(LoopStmt stmt) {
        if (stmt instanceof LoopStmt.WhileStmt)
            return visitWhileStmt((LoopStmt.WhileStmt) stmt);
        if (stmt instanceof LoopStmt.ForStmt)
            return visitForStmt((LoopStmt.ForStmt) stmt);
        if (stmt instanceof LoopStmt.RepeatUntilStmt)
            return visitRepeatUntilStmt((LoopStmt.RepeatUntilStmt) stmt);
        throw new UnsupportedOperationException("Loop type not supported: " + stmt.getClass().getSimpleName());
    }

    /**
     * Visits a 'while' loop.
     * Generates code that checks the condition, jumps to exit if false, executes
     * body,
     * and then jumps back to the condition.
     *
     * @param stmt The while statement AST node.
     * @return {@code null}.
     */
    @Override
    public Void visitWhileStmt(LoopStmt.WhileStmt stmt) {
        // while (condition) { body }
        //
        // loopStart:
        // <condition>
        // JUMP_IF_FALSE -> exitLoop
        // <body>
        // JUMP -> loopStart (continue target)
        // exitLoop:

        int loopStart = chunk.count;

        // Evaluate condition
        visitExpr(stmt.condition);
        chunk.write(OpCode.JUMP_IF_FALSE.ordinal(), stmt.loc.line(), stmt.loc.column());
        int jumpToExit = chunk.count;
        chunk.write(0, stmt.loc.line(), stmt.loc.column()); // placeholder

        // Push loop context (continueTarget = loopStart for while)
        LoopContext ctx = new LoopContext(loopStart);
        loopStack.push(ctx);

        // Body
        visitBlockStmt(stmt.body);

        // Jump back to condition
        chunk.write(OpCode.JUMP.ordinal(), stmt.loc.line(), stmt.loc.column());
        chunk.write(loopStart, stmt.loc.line(), stmt.loc.column());

        // Patch exit jump
        chunk.code[jumpToExit] = chunk.count;

        // Patch all break jumps to here
        loopStack.pop();
        for (int breakJump : ctx.breakJumps) {
            chunk.code[breakJump] = chunk.count;
        }

        return null;
    }

    /**
     * Visits a 'repeat-until' loop.
     * Generates code that executes the body once and then checks the condition
     * to determine whether to repeat.
     *
     * @param stmt The repeat-until statement AST node.
     * @return {@code null}.
     */
    @Override
    public Void visitRepeatUntilStmt(LoopStmt.RepeatUntilStmt stmt) {
        // repeat { body } until (condition)
        //
        // loopStart:
        // <body>
        // continueTarget:
        // <condition>
        // JUMP_IF_FALSE -> loopStart
        // exitLoop:

        int loopStart = chunk.count;

        // continueTarget will be patched after body
        LoopContext ctx = new LoopContext(-1); // placeholder, patched below
        loopStack.push(ctx);

        // Body
        visitBlockStmt(stmt.body);

        // Continue target is here (before condition eval)
        // We can't change the record field, so we use the breakJumps to
        // handle break, and for continue we re-check in visitContinueStmt
        // Actually, let's just use a mutable int for continueTarget.
        // Since we use a record, let's work around it:
        // Continue jumps emitted during body will be collected and patched here.
        int continueTarget = chunk.count;

        // Evaluate condition
        visitExpr(stmt.condition);
        chunk.write(OpCode.JUMP_IF_FALSE.ordinal(), stmt.loc.line(), stmt.loc.column());
        chunk.write(loopStart, stmt.loc.line(), stmt.loc.column()); // jump back if false

        // exitLoop is here
        loopStack.pop();
        for (int breakJump : ctx.breakJumps) {
            chunk.code[breakJump] = chunk.count;
        }

        return null;
    }

    /**
     * Visits a 'for' loop.
     * Dispatches to range-based or iterator-based loop compilation.
     *
     * @param stmt The for statement AST node.
     * @return {@code null}.
     */
    @Override
    public Void visitForStmt(LoopStmt.ForStmt stmt) {
        if (stmt.iterable instanceof RangeExpr) {
            return compileForRange(stmt, (RangeExpr) stmt.iterable);
        } else {
            return compileForIterable(stmt);
        }
    }

    /**
     * Compiles a for-loop that iterates over a range expression (e.g., {@code start..end}).
     * This is optimized to use a simple counter instead of creating a Range object.
     *
     * <p>
     * Internal logic breakdown:
     * </p>
     * 
     * <pre>
     * &lt;start&gt; -> SET_LOCAL $varName
     * &lt;end&gt;   -> SET_LOCAL $end
     * loopStart:
     * GET_LOCAL $varName
     * GET_LOCAL $end
     * LESS (or LESS_EQUAL for inclusive)
     * JUMP_IF_FALSE -> exitLoop
     * &lt;body&gt;
     * continueTarget:
     * GET_LOCAL $varName + 1 -> SET_LOCAL $varName
     * JUMP -> loopStart
     * exitLoop:
     * </pre>
     *
     * @param stmt  The for statement.
     * @param range The range expression.
     * @return {@code null}.
     */
    private Void compileForRange(LoopStmt.ForStmt stmt, RangeExpr range) {
        int line = stmt.loc.line();
        int col = stmt.loc.column();

        beginScope();

        // Initialize loop variable = range.start
        visitExpr(range.start);
        addLocal(stmt.varName);
        int varIdx = resolveLocal(stmt.varName);
        chunk.write(OpCode.SET_LOCAL.ordinal(), line, col);
        chunk.write(varIdx, line, col);
        chunk.write(OpCode.POP.ordinal(), line, col);

        // Store end value in a hidden local
        visitExpr(range.end);
        String endName = "$end_" + chunk.count;
        addLocal(endName);
        int endIdx = resolveLocal(endName);
        chunk.write(OpCode.SET_LOCAL.ordinal(), line, col);
        chunk.write(endIdx, line, col);
        chunk.write(OpCode.POP.ordinal(), line, col);

        // loopStart: condition check
        int loopStart = chunk.count;

        chunk.write(OpCode.GET_LOCAL.ordinal(), line, col);
        chunk.write(varIdx, line, col);
        chunk.write(OpCode.GET_LOCAL.ordinal(), line, col);
        chunk.write(endIdx, line, col);
        // 0..5 (exclusive) uses LESS, 0..=5 (inclusive) uses LESS_EQUAL
        chunk.write(range.inclusive ? OpCode.LESS_EQUAL.ordinal() : OpCode.LESS.ordinal(), line, col);

        chunk.write(OpCode.JUMP_IF_FALSE.ordinal(), line, col);
        int jumpToExit = chunk.count;
        chunk.write(0, line, col); // placeholder

        // Push loop context
        LoopContext ctx = new LoopContext(-1); // continueTarget deferred
        loopStack.push(ctx);

        // Body
        for (Statement s : stmt.body.statements) {
            visitStatement(s);
        }

        // continueTarget: increment loop variable
        int continueTarget = chunk.count;

        chunk.write(OpCode.GET_LOCAL.ordinal(), line, col);
        chunk.write(varIdx, line, col);
        int oneConst = chunk.addConstant(1);
        chunk.write(OpCode.LOAD_CONST.ordinal(), line, col);
        chunk.write(oneConst, line, col);
        chunk.write(OpCode.ADD.ordinal(), line, col);
        chunk.write(OpCode.SET_LOCAL.ordinal(), line, col);
        chunk.write(varIdx, line, col);
        chunk.write(OpCode.POP.ordinal(), line, col);

        // Jump back to loop start
        chunk.write(OpCode.JUMP.ordinal(), line, col);
        chunk.write(loopStart, line, col);

        // exitLoop:
        chunk.code[jumpToExit] = chunk.count;

        loopStack.pop();
        patchLoopJumps(ctx, continueTarget);

        endScope(line);
        return null;
    }

    /**
     * Compiles a for-loop that iterates over a general iterable object.
     * Uses internal VM opcodes for iteration.
     *
     * @param stmt The for statement.
     * @return {@code null}.
     */
    private Void compileForIterable(LoopStmt.ForStmt stmt) {
        int line = stmt.loc.line();
        int col = stmt.loc.column();

        beginScope();

        // Evaluate iterable and store in a hidden local
        visitExpr(stmt.iterable);
        String iterName = "$iter_" + chunk.count;
        addLocal(iterName);
        int iterIdx = resolveLocal(iterName);
        chunk.write(OpCode.SET_LOCAL.ordinal(), line, col);
        chunk.write(iterIdx, line, col);
        chunk.write(OpCode.POP.ordinal(), line, col);

        // Initialize index = 0
        int zeroConst = chunk.addConstant(0);
        chunk.write(OpCode.LOAD_CONST.ordinal(), line, col);
        chunk.write(zeroConst, line, col);
        String indexName = "$index_" + chunk.count;
        addLocal(indexName);
        int indexIdx = resolveLocal(indexName);
        chunk.write(OpCode.SET_LOCAL.ordinal(), line, col);
        chunk.write(indexIdx, line, col);
        chunk.write(OpCode.POP.ordinal(), line, col);

        // loopStart: check ITER_HAS_NEXT
        int loopStart = chunk.count;

        chunk.write(OpCode.GET_LOCAL.ordinal(), line, col);
        chunk.write(iterIdx, line, col);
        chunk.write(OpCode.GET_LOCAL.ordinal(), line, col);
        chunk.write(indexIdx, line, col);
        chunk.write(OpCode.ITER_HAS_NEXT.ordinal(), line, col);

        chunk.write(OpCode.JUMP_IF_FALSE.ordinal(), line, col);
        int jumpToExit = chunk.count;
        chunk.write(0, line, col); // placeholder

        // Get current element: ITER_NEXT
        chunk.write(OpCode.GET_LOCAL.ordinal(), line, col);
        chunk.write(iterIdx, line, col);
        chunk.write(OpCode.GET_LOCAL.ordinal(), line, col);
        chunk.write(indexIdx, line, col);
        chunk.write(OpCode.ITER_NEXT.ordinal(), line, col);

        // Bind to loop variable
        addLocal(stmt.varName);
        int varIdx = resolveLocal(stmt.varName);
        chunk.write(OpCode.SET_LOCAL.ordinal(), line, col);
        chunk.write(varIdx, line, col);
        chunk.write(OpCode.POP.ordinal(), line, col);

        // Push loop context
        LoopContext ctx = new LoopContext(-1); // continueTarget deferred
        loopStack.push(ctx);

        // Body
        for (Statement s : stmt.body.statements) {
            visitStatement(s);
        }

        // continueTarget: increment index
        int continueTarget = chunk.count;

        chunk.write(OpCode.GET_LOCAL.ordinal(), line, col);
        chunk.write(indexIdx, line, col);
        int oneConst = chunk.addConstant(1);
        chunk.write(OpCode.LOAD_CONST.ordinal(), line, col);
        chunk.write(oneConst, line, col);
        chunk.write(OpCode.ADD.ordinal(), line, col);
        chunk.write(OpCode.SET_LOCAL.ordinal(), line, col);
        chunk.write(indexIdx, line, col);
        chunk.write(OpCode.POP.ordinal(), line, col);

        // Jump back to loop start
        chunk.write(OpCode.JUMP.ordinal(), line, col);
        chunk.write(loopStart, line, col);

        // exitLoop:
        chunk.code[jumpToExit] = chunk.count;

        loopStack.pop();
        patchLoopJumps(ctx, continueTarget);

        endScope(line);
        return null;
    }

    /**
     * Patches forward jumps (break/continue) for the current loop.
     * Jump placeholders with value {@code -1} are {@code continue} jumps and are
     * patched to {@code continueTarget}. Jump placeholders with value {@code 0}
     * are {@code break} jumps and are patched to the instruction just past the loop.
     *
     * @param ctx            The loop context containing jumps to patch.
     * @param continueTarget The index of the instruction to jump to for continue.
     */
    private void patchLoopJumps(LoopContext ctx, int continueTarget) {
        for (int jumpIdx : ctx.breakJumps) {
            if (chunk.code[jumpIdx] == -1) {
                // deferred continue: jump to the increment/condition check
                chunk.code[jumpIdx] = continueTarget;
            } else {
                // break: jump past the loop exit
                chunk.code[jumpIdx] = chunk.count;
            }
        }
    }

    /**
     * Compiles a try-catch-finally statement.
     *
     * <p>
     * Emits a {@code TRY <catchPc>} opcode that registers a handler in the current
     * frame.
     * When an exception reaches the VM's {@code handleException}, it restores the
     * operand stack
     * to the saved depth and pushes the caught Aurora object, then jumps to
     * {@code catchPc}.
     * Multiple catch clauses are chained with type checks ({@code IS}) and
     * conditional jumps.
     * A {@code finally} block, if present, is inlined into both the normal and
     * exception paths.
     * </p>
     *
     * @param stmt The try statement AST node.
     * @return {@code null}.
     */
    @Override
    public Void visitTryStmt(TryStmt stmt) {
        int line = stmt.loc.line(), col = stmt.loc.column();

        // --- try block ---
        // Emit TRY with a placeholder for the catch PC.
        chunk.write(OpCode.TRY.ordinal(), line, col);
        int tryHandlerIdx = chunk.count;
        chunk.write(0, line, col); // placeholder: patched to first catch entry

        visitBlockStmt(stmt.tryBlock);

        // Normal exit: pop the handler, jump past all catch blocks.
        chunk.write(OpCode.END_TRY.ordinal(), line, col);

        // Inline finally (normal path) before the jump past catches.
        if (stmt.finallyBlock != null) {
            visitBlockStmt(stmt.finallyBlock);
        }

        chunk.write(OpCode.JUMP.ordinal(), line, col);
        int jumpPastCatches = chunk.count;
        chunk.write(0, line, col); // placeholder: patched to end

        // --- catch clauses ---
        // Patch TRY handler to here (start of catch dispatch).
        chunk.code[tryHandlerIdx] = chunk.count;

        if (stmt.catches.isEmpty()) {
            // No catch clauses, just a finally block.
            // The exception object is on the stack; pop and run finally, then re-throw.
            if (stmt.finallyBlock != null) {
                // Save exception to a hidden local, run finally, then re-throw.
                beginScope();
                String exName = "$ex_" + chunk.count;
                addLocal(exName);
                int exIdx = resolveLocal(exName);
                chunk.write(OpCode.SET_LOCAL.ordinal(), line, col);
                chunk.write(exIdx, line, col);
                chunk.write(OpCode.POP.ordinal(), line, col);

                visitBlockStmt(stmt.finallyBlock);

                chunk.write(OpCode.GET_LOCAL.ordinal(), line, col);
                chunk.write(exIdx, line, col);
                chunk.write(OpCode.THROW.ordinal(), line, col);
                endScope(line);
            } else {
                // Re-throw immediately.
                chunk.write(OpCode.THROW.ordinal(), line, col);
            }
        } else {
            // The caught exception object is on top of the stack at this point.
            // Save it to a hidden local for type-checking across clauses.
            beginScope();
            String exName = "$ex_" + chunk.count;
            addLocal(exName);
            int exIdx = resolveLocal(exName);
            chunk.write(OpCode.SET_LOCAL.ordinal(), line, col);
            chunk.write(exIdx, line, col);
            chunk.write(OpCode.POP.ordinal(), line, col);

            List<Integer> jumpsToEnd = new ArrayList<>();

            for (TryStmt.CatchClause clause : stmt.catches) {
                int clauseLine = clause.block().loc.line();

                // Type check: load exception and check IS <type>
                chunk.write(OpCode.GET_LOCAL.ordinal(), clauseLine, col);
                chunk.write(exIdx, clauseLine, col);

                String typeName = clause.type() != null ? clause.type().name : "object";
                int typeNameIdx = chunk.addConstant(typeName);
                chunk.write(OpCode.GET_GLOBAL.ordinal(), clauseLine, col);
                chunk.write(typeNameIdx, clauseLine, col);
                chunk.write(OpCode.IS.ordinal(), clauseLine, col);

                chunk.write(OpCode.JUMP_IF_FALSE.ordinal(), clauseLine, col);
                int jumpToNext = chunk.count;
                chunk.write(0, clauseLine, col); // patched to next clause

                // Bind the exception to the catch variable.
                beginScope();
                addLocal(clause.var());
                int varIdx = resolveLocal(clause.var());
                chunk.write(OpCode.GET_LOCAL.ordinal(), clauseLine, col);
                chunk.write(exIdx, clauseLine, col);
                chunk.write(OpCode.SET_LOCAL.ordinal(), clauseLine, col);
                chunk.write(varIdx, clauseLine, col);
                chunk.write(OpCode.POP.ordinal(), clauseLine, col);

                visitBlockStmt(clause.block());
                endScope(clauseLine);

                // Inline finally (exception path) after each catch body.
                if (stmt.finallyBlock != null) {
                    visitBlockStmt(stmt.finallyBlock);
                }

                chunk.write(OpCode.JUMP.ordinal(), clauseLine, col);
                jumpsToEnd.add(chunk.count);
                chunk.write(0, clauseLine, col); // patched to end

                chunk.code[jumpToNext] = chunk.count;
            }

            // No catch matched — re-throw.
            chunk.write(OpCode.GET_LOCAL.ordinal(), line, col);
            chunk.write(exIdx, line, col);

            if (stmt.finallyBlock != null) {
                visitBlockStmt(stmt.finallyBlock);
            }

            chunk.write(OpCode.THROW.ordinal(), line, col);

            endScope(line);

            for (int j : jumpsToEnd) {
                chunk.code[j] = chunk.count;
            }
        }

        // Patch the jump past catch clauses to here.
        chunk.code[jumpPastCatches] = chunk.count;

        return null;
    }

    @Override
    public Void visitTryStmtCatch(TryStmt.CatchClause catchClause) {
        return null; // Handled inline by visitTryStmt
    }

    /**
     * Visits a match statement.
     *
     * @param stmt The match statement AST node.
     * @return {@code null}.
     */
    @Override
    public Void visitMatchStmt(MatchStmt stmt) {
        compileMatch(stmt.expression, stmt.cases, stmt.loc.line(), false);
        return null;
    }

    @Override
    public Void visitMatchCase(MatchStmt.MatchCase matchCase) {
        return null; // Handled by compileMatch
    }

    @Override
    public Void visitMatchPattern(MatchStmt.Pattern pattern) {
        return null; // Handled by compileMatch
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

    @Override
    public Void visitLabeledStmt(LabeledStmt stmt) {
        return null;
    }

    @Override
    public Void visitInitializerBlock(InitializerBlock stmt) {
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

    /**
     * Visits a return statement.
     * Evaluates the return value and emits the RETURN opcode.
     *
     * @param stmt The return statement AST node.
     * @return {@code null}.
     */
    @Override
    public Void visitReturnStmt(ControlStmt.ReturnStmt stmt) {
        if (stmt.value != null) {
            visitExpr(stmt.value);
        } else {
            int noneIndex = chunk.addConstant(null);
            chunk.write(OpCode.LOAD_CONST.ordinal(), stmt.loc.line(), stmt.loc.column());
            chunk.write(noneIndex, stmt.loc.line(), stmt.loc.column());
        }
        chunk.write(OpCode.RETURN.ordinal(), stmt.loc.line(), stmt.loc.column());
        return null;
    }

    /**
     * Visits a throw statement.
     * Evaluates the exception expression and emits the {@code THROW} opcode.
     *
     * @param stmt The throw statement AST node.
     * @return {@code null}.
     */
    @Override
    public Void visitThrowStmt(ControlStmt.ThrowStmt stmt) {
        visitExpr(stmt.value);
        chunk.write(OpCode.THROW.ordinal(), stmt.loc.line(), stmt.loc.column());
        return null;
    }

    /**
     * Visits a break statement.
     * Registers a jump placeholder that will be patched at the loop exit.
     *
     * @param stmt The break statement AST node.
     * @return {@code null}.
     */
    @Override
    public Void visitBreakStmt(ControlStmt.BreakStmt stmt) {
        if (loopStack.isEmpty()) {
            throw new RuntimeException("'break' used outside of a loop.");
        }
        // Emit a jump placeholder — will be patched when the loop ends
        chunk.write(OpCode.JUMP.ordinal(), stmt.loc.line(), stmt.loc.column());
        int jumpIdx = chunk.count;
        chunk.write(0, stmt.loc.line(), stmt.loc.column()); // placeholder, patched at loop exit
        loopStack.peek().breakJumps.add(jumpIdx);
        return null;
    }

    @Override
    public Void visitContinueStmt(ControlStmt.ContinueStmt stmt) {
        if (loopStack.isEmpty()) {
            throw new RuntimeException("'continue' used outside of a loop.");
        }
        LoopContext ctx = loopStack.peek();
        if (ctx.continueTarget >= 0) {
            // While loop: continueTarget is known (loop start)
            chunk.write(OpCode.JUMP.ordinal(), stmt.loc.line(), stmt.loc.column());
            chunk.write(ctx.continueTarget, stmt.loc.line(), stmt.loc.column());
        } else {
            // For loop: continueTarget is not yet known, store as deferred
            chunk.write(OpCode.JUMP.ordinal(), stmt.loc.line(), stmt.loc.column());
            int jumpIdx = chunk.count;
            chunk.write(-1, stmt.loc.line(), stmt.loc.column()); // -1 marker for continue
            ctx.breakJumps.add(jumpIdx);
        }
        return null;
    }

    private CompiledFunction compileFunction(FunctionDecl decl, boolean isInstanceMethod) {
        if (decl.modifiers.contains(FunctionModifier.NATIVE)) {
            // ... (native implementation) ...
            String fullName = fqn(decl.name);
            int nameIndex = this.chunk.addConstant(fullName);

            // 1. Get from VM's pre-registered native functions
            this.chunk.write(OpCode.GET_GLOBAL.ordinal(), decl.loc.line(), decl.loc.column());
            this.chunk.write(nameIndex, decl.loc.line(), decl.loc.column());

            // 2. If it's in a namespace/class, also register it under simple name
            if (!fullName.equals(decl.name)) {
                int simpleNameIndex = this.chunk.addConstant(decl.name);
                this.chunk.write(OpCode.SET_GLOBAL.ordinal(), decl.loc.line(), decl.loc.column());
                this.chunk.write(simpleNameIndex, decl.loc.line(), decl.loc.column());
            }

            // 3. Register under full name
            this.chunk.write(OpCode.SET_GLOBAL.ordinal(), decl.loc.line(), decl.loc.column());
            this.chunk.write(nameIndex, decl.loc.line(), decl.loc.column());
            this.chunk.write(OpCode.POP.ordinal(), decl.loc.line(), decl.loc.column());

            // 4. Create a wrapper CompiledFunction that calls the native function
            Chunk wrapperChunk = new Chunk(this.currentFileName);
            int nameIdx = wrapperChunk.addConstant(fullName);

            int argCount = decl.params.size();
            int offset = isInstanceMethod ? 1 : 0;
            // Total args passed to the native fn: self (if instance) + declared params
            int totalCallArgs = argCount + offset;

            // CALL expects: callee pushed first (bottom), then args on top.
            // Push callee first so it ends up below the args on the stack.
            wrapperChunk.write(OpCode.GET_GLOBAL.ordinal(), decl.loc.line(), decl.loc.column());
            wrapperChunk.write(nameIdx, decl.loc.line(), decl.loc.column());

            if (isInstanceMethod) {
                // Load self from slot 0
                wrapperChunk.write(OpCode.GET_LOCAL.ordinal(), decl.loc.line(), decl.loc.column());
                wrapperChunk.write(0, decl.loc.line(), decl.loc.column());
            }

            for (int i = 0; i < argCount; i++) {
                wrapperChunk.write(OpCode.GET_LOCAL.ordinal(), decl.loc.line(), decl.loc.column());
                wrapperChunk.write(i + offset, decl.loc.line(), decl.loc.column());
            }
            wrapperChunk.write(OpCode.CALL.ordinal(), decl.loc.line(), decl.loc.column());
            wrapperChunk.write(totalCallArgs, decl.loc.line(), decl.loc.column());
            wrapperChunk.write(OpCode.RETURN.ordinal(), decl.loc.line(), decl.loc.column());

            return new CompiledFunction(decl.name, wrapperChunk, argCount + (isInstanceMethod ? 1 : 0));
        }

        Chunk parentChunk = this.chunk;
        List<Local> parentLocals = this.locals;
        int parentScopeDepth = this.scopeDepth;
        boolean oldInInstanceMethod = this.inInstanceMethod;

        this.chunk = new Chunk(this.currentFileName);
        this.locals = new ArrayList<>();
        this.scopeDepth = 0;
        this.inInstanceMethod = isInstanceMethod;

        if (isInstanceMethod) {
            addLocal("self");
        }

        for (ParamDecl param : decl.params) {
            addLocal(param.name);
        }

        if (decl.body != null) {
            visitBlockStmt(decl.body);
        }

        // Implicit return
        int noneIndex = this.chunk.addConstant(null);
        this.chunk.write(OpCode.LOAD_CONST.ordinal(), decl.loc.line(), decl.loc.column());
        this.chunk.write(noneIndex, decl.loc.line(), decl.loc.column());
        this.chunk.write(OpCode.RETURN.ordinal(), decl.loc.line(), decl.loc.column());

        CompiledFunction function = new CompiledFunction(decl.name, this.chunk,
                decl.params.size() + (isInstanceMethod ? 1 : 0));

        this.chunk = parentChunk;
        this.locals = parentLocals;
        this.scopeDepth = parentScopeDepth;
        this.inInstanceMethod = oldInInstanceMethod;

        return function;
    }

    @Override
    public Void visitClassDecl(ClassDecl decl) {
        String fullName = fqn(decl.name);
        CompiledClass cls = new CompiledClass(fullName);
        if (decl.superClass != null) {
            cls.superClassName = fqn(decl.superClass.name);
        }

        for (TypeNode iface : decl.interfaces) {
            cls.interfaceNames.add(fqn(iface.name));
        }

        // Save context
        String oldNamespace = this.currentNamespace;
        this.currentNamespace = fullName; // Methods are inside class namespace

        ClassDecl oldClass = this.currentClass;
        this.currentClass = decl;

        // 1. Collect field initializations
        List<Statement> instanceFieldInits = new ArrayList<>();
        for (Declaration member : decl.members) {
            if (member instanceof FieldDecl field && field.init != null && !field._static) {
                // self.fieldName = initExpr
                AccessExpr target = new AccessExpr(field.loc, new SelfExpr(field.loc), field.name, false, false);
                BinaryExpr assignment = new BinaryExpr(field.loc, target, field.init, BinaryOperator.ASSIGN);
                instanceFieldInits.add(new ExprStmt(field.loc, assignment));
            }
        }

        // 2. Find constructor or create one if we have field initializations
        ConstructorDecl constructor = null;
        for (Declaration member : decl.members) {
            if (member instanceof ConstructorDecl cons) {
                constructor = cons;
                break;
            }
        }

        if (constructor != null) {
            // Prepend field initializations to the existing constructor body
            if (constructor.body instanceof BlockStmt block) {
                List<Statement> newStmts = new ArrayList<>(instanceFieldInits);
                newStmts.addAll(block.statements);
                constructor = new ConstructorDecl(constructor.loc, constructor.visibility, constructor.params,
                        new BlockStmt(constructor.body.loc, newStmts));
            }
        } else if (!instanceFieldInits.isEmpty()) {
            // Create a default constructor
            constructor = new ConstructorDecl(decl.loc, Visibility.PUBLIC, List.of(),
                    new BlockStmt(decl.loc, instanceFieldInits));
        }

        // 3. Compile members
        for (Declaration member : decl.members) {
            if (member instanceof FunctionDecl func) {
                boolean isStatic = func.modifiers.contains(FunctionModifier.STATIC);
                CompiledFunction cf = compileFunction(func, !isStatic);
                cls.methods.put(func.name, cf);
            }
        }

        if (constructor != null) {
            FunctionDecl func = new FunctionDecl(constructor.loc, "<init>", null, constructor.visibility,
                    List.of(), List.of(), constructor.params, null, constructor.body, false);
            CompiledFunction cf = compileFunction(func, true);
            cls.initializer = cf;
            cls.methods.put("<init>", cf);
        }

        this.currentNamespace = oldNamespace;
        this.currentClass = oldClass;

        int nameIndex = chunk.addConstant(fullName);
        int clsIndex = chunk.addConstant(cls);
        chunk.write(OpCode.LOAD_CONST.ordinal(), decl.loc.line(), decl.loc.column());
        chunk.write(clsIndex, decl.loc.line(), decl.loc.column());

        chunk.write(OpCode.SET_GLOBAL.ordinal(), decl.loc.line(), decl.loc.column());
        chunk.write(nameIndex, decl.loc.line(), decl.loc.column());
        chunk.write(OpCode.POP.ordinal(), decl.loc.line(), decl.loc.column());

        if (!fullName.equals(decl.name)) {
            int simpleNameIndex = chunk.addConstant(decl.name);
            chunk.write(OpCode.LOAD_CONST.ordinal(), decl.loc.line(), decl.loc.column());
            chunk.write(clsIndex, decl.loc.line(), decl.loc.column());
            chunk.write(OpCode.SET_GLOBAL.ordinal(), decl.loc.line(), decl.loc.column());
            chunk.write(simpleNameIndex, decl.loc.line(), decl.loc.column());
            chunk.write(OpCode.POP.ordinal(), decl.loc.line(), decl.loc.column());
        }

        return null;
    }

    @Override
    public Void visitClassParamDecl(ClassParamDecl decl) {
        return null;
    }

    /**
     * Visits an enum declaration.
     *
     * <p>
     * Compiles an enum by creating an {@code ArClass} representing the enum type
     * and
     * an {@code ArInstance} of that class for each member. Each member instance is
     * stored
     * as a global under {@code "EnumName"} (the class itself) and
     * {@code "EnumName::MemberName"} (the member value) to support
     * {@code Mode::Read} access.
     * Numeric ordinal values are stored in a field named {@code "ordinal"} on each
     * instance.
     * If a member declares an explicit integer value via {@code = expr}, that value
     * is used;
     * otherwise ordinals are assigned sequentially from 0.
     * </p>
     *
     * @param decl The enum declaration AST node.
     * @return {@code null}.
     */
    @Override
    public Void visitEnumDecl(EnumDecl decl) {
        String fullName = fqn(decl.name);
        int line = decl.loc.line(), col = decl.loc.column();

        // 1. Create the enum class object and register it as a global.
        CompiledClass enumClass = new CompiledClass(fullName);
        enumClass.isEnum = true;

        int clsIdx = chunk.addConstant(enumClass);
        int fqnIdx = chunk.addConstant(fullName);

        chunk.write(OpCode.LOAD_CONST.ordinal(), line, col);
        chunk.write(clsIdx, line, col);
        chunk.write(OpCode.SET_GLOBAL.ordinal(), line, col);
        chunk.write(fqnIdx, line, col);
        chunk.write(OpCode.POP.ordinal(), line, col);

        if (!fullName.equals(decl.name)) {
            int simpleIdx = chunk.addConstant(decl.name);
            chunk.write(OpCode.LOAD_CONST.ordinal(), line, col);
            chunk.write(clsIdx, line, col);
            chunk.write(OpCode.SET_GLOBAL.ordinal(), line, col);
            chunk.write(simpleIdx, line, col);
            chunk.write(OpCode.POP.ordinal(), line, col);
        }

        // 2. Create one instance per member and register as "EnumName::MemberName".
        int nextOrdinal = 0;
        for (EnumDecl.EnumMember member : decl.members) {
            int memberLine = member.loc.line();

            // Push the enum class, call NEW with 0 args to create instance.
            chunk.write(OpCode.GET_GLOBAL.ordinal(), memberLine, col);
            chunk.write(fqnIdx, memberLine, col);
            chunk.write(OpCode.NEW.ordinal(), memberLine, col);
            chunk.write(0, memberLine, col);

            // Set the built-in "name" field.
            String namePropName = "name";
            int memberNameIdx = chunk.addConstant(member.name);
            int namePropIdx = chunk.addConstant(namePropName);

            // Duplicate instance on stack: SET_PROPERTY pops the instance,
            // so we use GET/SET pattern via a hidden local.
            String hiddenLocal = "$enum_" + decl.name + "_" + member.name;
            beginScope();
            addLocal(hiddenLocal);
            int hiddenIdx = resolveLocal(hiddenLocal);

            chunk.write(OpCode.SET_LOCAL.ordinal(), memberLine, col);
            chunk.write(hiddenIdx, memberLine, col);
            chunk.write(OpCode.POP.ordinal(), memberLine, col);

            // Set "name" field.
            chunk.write(OpCode.GET_LOCAL.ordinal(), memberLine, col);
            chunk.write(hiddenIdx, memberLine, col);
            chunk.write(OpCode.LOAD_CONST.ordinal(), memberLine, col);
            chunk.write(memberNameIdx, memberLine, col);
            chunk.write(OpCode.SET_PROPERTY.ordinal(), memberLine, col);
            chunk.write(namePropIdx, memberLine, col);
            chunk.write(OpCode.POP.ordinal(), memberLine, col);

            // Set "ordinal" field.
            int ordinalVal;
            if (member.value instanceof LiteralExpr litExpr &&
                    (litExpr.type == LiteralExpr.LiteralType.INT || litExpr.type == LiteralExpr.LiteralType.LONG)) {
                ordinalVal = ((Number) litExpr.value).intValue();
                nextOrdinal = ordinalVal + 1;
            } else {
                ordinalVal = nextOrdinal++;
            }
            int ordinalConstIdx = chunk.addConstant(ordinalVal);
            int ordinalPropIdx = chunk.addConstant("ordinal");

            chunk.write(OpCode.GET_LOCAL.ordinal(), memberLine, col);
            chunk.write(hiddenIdx, memberLine, col);
            chunk.write(OpCode.LOAD_CONST.ordinal(), memberLine, col);
            chunk.write(ordinalConstIdx, memberLine, col);
            chunk.write(OpCode.SET_PROPERTY.ordinal(), memberLine, col);
            chunk.write(ordinalPropIdx, memberLine, col);
            chunk.write(OpCode.POP.ordinal(), memberLine, col);

            // Register as global: "EnumName::MemberName" and "EnumName.MemberName".
            String memberQName = fullName + "::" + member.name;
            int memberQNameIdx = chunk.addConstant(memberQName);

            chunk.write(OpCode.GET_LOCAL.ordinal(), memberLine, col);
            chunk.write(hiddenIdx, memberLine, col);
            chunk.write(OpCode.SET_GLOBAL.ordinal(), memberLine, col);
            chunk.write(memberQNameIdx, memberLine, col);
            chunk.write(OpCode.POP.ordinal(), memberLine, col);

            if (!fullName.equals(decl.name)) {
                String simpleMemberQName = decl.name + "::" + member.name;
                int simpleMemberQNameIdx = chunk.addConstant(simpleMemberQName);
                chunk.write(OpCode.GET_LOCAL.ordinal(), memberLine, col);
                chunk.write(hiddenIdx, memberLine, col);
                chunk.write(OpCode.SET_GLOBAL.ordinal(), memberLine, col);
                chunk.write(simpleMemberQNameIdx, memberLine, col);
                chunk.write(OpCode.POP.ordinal(), memberLine, col);
            }

            endScope(memberLine);
        }

        return null;
    }

    @Override
    public Void visitEnumMember(EnumDecl.EnumMember member) {
        return null; // Handled inline by visitEnumDecl
    }

    @Override
    public Void visitConstructorDecl(ConstructorDecl decl) {
        return null;
    }

    @Override
    public Void visitFunctionDecl(FunctionDecl decl) {
        CompiledFunction function = compileFunction(decl, false);

        int funcIndex = this.chunk.addConstant(function);
        this.chunk.write(OpCode.LOAD_CONST.ordinal(), decl.loc.line(), decl.loc.column());
        this.chunk.write(funcIndex, decl.loc.line(), decl.loc.column());

        String fullName = fqn(decl.name);
        if (scopeDepth > 0) {
            addLocal(decl.name);
        } else {
            int nameIndex = this.chunk.addConstant(fullName);
            this.chunk.write(OpCode.SET_GLOBAL.ordinal(), decl.loc.line(), decl.loc.column());
            this.chunk.write(nameIndex, decl.loc.line(), decl.loc.column());
            this.chunk.write(OpCode.POP.ordinal(), decl.loc.line(), decl.loc.column());

            if (!fullName.equals(decl.name)) {
                int simpleNameIndex = this.chunk.addConstant(decl.name);
                this.chunk.write(OpCode.LOAD_CONST.ordinal(), decl.loc.line(), decl.loc.column());
                this.chunk.write(funcIndex, decl.loc.line(), decl.loc.column());
                this.chunk.write(OpCode.SET_GLOBAL.ordinal(), decl.loc.line(), decl.loc.column());
                this.chunk.write(simpleNameIndex, decl.loc.line(), decl.loc.column());
                this.chunk.write(OpCode.POP.ordinal(), decl.loc.line(), decl.loc.column());
            }
        }

        return null;
    }

    @Override
    public Void visitThreadDecl(ThreadDecl decl) {
        return null;
    }

    @Override
    public Void visitRecordDecl(RecordDecl decl) {
        return null;
    }

    @Override
    public Void visitParamDecl(ParamDecl decl) {
        return null;
    }

    @Override
    public Void visitMethodSignature(MethodSignature sig) {
        return null;
    }

    @Override
    public Void visitInterfaceDecl(InterfaceDecl decl) {
        String fullName = fqn(decl.name);
        CompiledClass cls = new CompiledClass(fullName);
        cls.isTrait = true; // CompiledClass にフラグ追加

        for (Declaration member : decl.members) {
            if (member instanceof FunctionDecl func) {
                CompiledFunction cf = compileFunction(func, true);
                cls.methods.put(func.name, cf);
            }
        }

        int nameIndex = chunk.addConstant(fullName);
        int clsIndex = chunk.addConstant(cls);
        chunk.write(OpCode.LOAD_CONST.ordinal(), decl.loc.line(), decl.loc.column());
        chunk.write(clsIndex, decl.loc.line(), decl.loc.column());
        chunk.write(OpCode.SET_GLOBAL.ordinal(), decl.loc.line(), decl.loc.column());
        chunk.write(nameIndex, decl.loc.line(), decl.loc.column());
        chunk.write(OpCode.POP.ordinal(), decl.loc.line(), decl.loc.column());

        return null;
    }

    @Override
    public Void visitLambdaExpr(LambdaExpr expr) {
        Chunk parentChunk = this.chunk;
        List<Local> parentLocals = this.locals;
        int parentScopeDepth = this.scopeDepth;

        this.chunk = new Chunk(this.currentFileName);
        this.locals = new ArrayList<>();
        this.scopeDepth = 0;

        for (ParamDecl param : expr.params) {
            addLocal(param.name);
        }

        if (expr.body instanceof BlockStmt) {
            visitBlockStmt((BlockStmt) expr.body);
        } else if (expr.body instanceof Expr) {
            visitExpr((Expr) expr.body);
            this.chunk.write(OpCode.RETURN.ordinal(), expr.loc.line(), expr.loc.column());
        }

        int noneIndex = this.chunk.addConstant(null);
        this.chunk.write(OpCode.LOAD_CONST.ordinal(), expr.loc.line(), expr.loc.column());
        this.chunk.write(noneIndex, expr.loc.line(), expr.loc.column());
        this.chunk.write(OpCode.RETURN.ordinal(), expr.loc.line(), expr.loc.column());

        CompiledFunction function = new CompiledFunction("lambda", this.chunk, expr.params.size());

        this.chunk = parentChunk;
        this.locals = parentLocals;
        this.scopeDepth = parentScopeDepth;

        int funcIndex = this.chunk.addConstant(function);
        this.chunk.write(OpCode.LOAD_CONST.ordinal(), expr.loc.line(), expr.loc.column());
        this.chunk.write(funcIndex, expr.loc.line(), expr.loc.column());

        return null;
    }

    @Override
    public Void visitIfExpr(IfExpr expr) {
        List<Integer> jumpsToEnd = new ArrayList<>();
        int noneIndex = chunk.addConstant(null);

        // 1. Main If
        visitExpr(expr.condition);
        chunk.write(OpCode.JUMP_IF_FALSE.ordinal(), expr.loc.line(), expr.loc.column());
        int jumpToNextBranchIndex = chunk.count;
        chunk.write(0, expr.loc.line(), expr.loc.column()); // Placeholder

        visitBlockStmt(expr.thenBlock);
        // Push null as result of block (temporary hack until blocks return values)
        chunk.write(OpCode.LOAD_CONST.ordinal(), expr.loc.line(), expr.loc.column());
        chunk.write(noneIndex, expr.loc.line(), expr.loc.column());

        chunk.write(OpCode.JUMP.ordinal(), expr.loc.line(), expr.loc.column());
        jumpsToEnd.add(chunk.count);
        chunk.write(0, expr.loc.line(), expr.loc.column()); // Placeholder

        // Patch jumpToNextBranch to here
        chunk.code[jumpToNextBranchIndex] = chunk.count;

        // 2. Else Ifs
        if (expr.elseIfs != null) {
            for (IfStmt.ElseIf elseIf : expr.elseIfs) {
                visitExpr(elseIf.condition);
                chunk.write(OpCode.JUMP_IF_FALSE.ordinal(), elseIf.loc.line(), elseIf.loc.column());
                jumpToNextBranchIndex = chunk.count;
                chunk.write(0, elseIf.loc.line(), elseIf.loc.column()); // Placeholder

                visitBlockStmt(elseIf.block);
                // Push null
                chunk.write(OpCode.LOAD_CONST.ordinal(), elseIf.loc.line(), elseIf.loc.column());
                chunk.write(noneIndex, elseIf.loc.line(), elseIf.loc.column());

                chunk.write(OpCode.JUMP.ordinal(), elseIf.loc.line(), elseIf.loc.column());
                jumpsToEnd.add(chunk.count);
                chunk.write(0, elseIf.loc.line(), elseIf.loc.column()); // Placeholder

                // Patch jumpToNextBranch to here
                chunk.code[jumpToNextBranchIndex] = chunk.count;
            }
        }

        // 3. Else
        if (expr.elseBlock != null) {
            visitBlockStmt(expr.elseBlock);
            // Push null
            chunk.write(OpCode.LOAD_CONST.ordinal(), expr.loc.line(), expr.loc.column());
            chunk.write(noneIndex, expr.loc.line(), expr.loc.column());
        } else {
            // If no else block, and condition is false, IfExpr returns what?
            // Usually ArNone or Null.
            // We need to push null here too if we fell through.
            chunk.write(OpCode.LOAD_CONST.ordinal(), expr.loc.line(), expr.loc.column());
            chunk.write(noneIndex, expr.loc.line(), expr.loc.column());
        }

        // Patch all jumpsToEnd to here
        int endIndex = chunk.count;
        for (int jumpIndex : jumpsToEnd) {
            chunk.code[jumpIndex] = endIndex;
        }

        return null;
    }

    @Override
    public Void visitProgramPackage(Program.Package pkg) {
        this.currentNamespace = pkg.namespace;
        return null;
    }

    /**
     * Visits an Elvis (null-coalescing) expression ({@code left ?: right}).
     *
     * <p>
     * Evaluates {@code left}. If it is not {@code none} (non-null), the result
     * is {@code left}. Otherwise the result is {@code right}.
     * The generated bytecode avoids evaluating {@code right} when {@code left} is
     * already present (short-circuit evaluation).
     *
     * <p>
     * Bytecode layout:
     * 
     * <pre>
     *   &lt;left&gt;
     *   SET_LOCAL $tmp          ; save left temporarily
     *   POP
     *   GET_LOCAL $tmp
     *   GET_GLOBAL none
     *   NOT_EQUAL               ; is left != none?
     *   JUMP_IF_FALSE -&gt; useRight
     *   GET_LOCAL $tmp          ; result = left
     *   JUMP -&gt; end
     * useRight:
     *   &lt;right&gt;               ; result = right
     * end:
     * </pre>
     *
     * @param expr The Elvis expression AST node.
     * @return {@code null}.
     */
    @Override
    public Void visitElvisExpr(ElvisExpr expr) {
        int line = expr.loc.line(), col = expr.loc.column();

        // Evaluate left and stash in a hidden local.
        visitExpr(expr.left);
        beginScope();
        String tmpName = "$elvis_" + chunk.count;
        addLocal(tmpName);
        int tmpIdx = resolveLocal(tmpName);
        chunk.write(OpCode.SET_LOCAL.ordinal(), line, col);
        chunk.write(tmpIdx, line, col);
        chunk.write(OpCode.POP.ordinal(), line, col);

        // Check: tmp != none
        chunk.write(OpCode.GET_LOCAL.ordinal(), line, col);
        chunk.write(tmpIdx, line, col);
        int noneIdx = chunk.addConstant(null);
        chunk.write(OpCode.LOAD_CONST.ordinal(), line, col);
        chunk.write(noneIdx, line, col);
        chunk.write(OpCode.NOT_EQUAL.ordinal(), line, col);

        chunk.write(OpCode.JUMP_IF_FALSE.ordinal(), line, col);
        int jumpToRight = chunk.count;
        chunk.write(0, line, col); // placeholder

        // Left is non-null: push it as the result.
        chunk.write(OpCode.GET_LOCAL.ordinal(), line, col);
        chunk.write(tmpIdx, line, col);
        chunk.write(OpCode.JUMP.ordinal(), line, col);
        int jumpToEnd = chunk.count;
        chunk.write(0, line, col); // placeholder

        // Left is null/none: evaluate right.
        chunk.code[jumpToRight] = chunk.count;
        visitExpr(expr.right);

        chunk.code[jumpToEnd] = chunk.count;
        endScope(line);

        return null;
    }

    /**
     * Visits a standalone range expression (e.g., {@code 1..10} or {@code 1..=10}).
     * Emits the start and end expressions and a {@code NEW_RANGE} opcode to create
     * an Aurora.Runtime.Range object on the stack.
     * When used directly as a {@code for}-loop iterable, ranges are compiled by
     * {@link #compileForRange} instead and this method is not called.
     *
     * @param expr The range expression AST node.
     * @return {@code null}.
     */
    @Override
    public Void visitRangeExpr(RangeExpr expr) {
        int nameIndex = chunk.addConstant("Aurora.Runtime.Range");
        chunk.write(OpCode.GET_GLOBAL.ordinal(), expr.loc.line(), expr.loc.column());
        chunk.write(nameIndex, expr.loc.line(), expr.loc.column());

        visitExpr(expr.start);
        visitExpr(expr.end);

        if (expr.inclusive) {
            chunk.write(OpCode.TRUE.ordinal(), expr.loc.line(), expr.loc.column());
        } else {
            chunk.write(OpCode.FALSE.ordinal(), expr.loc.line(), expr.loc.column());
        }

        chunk.write(OpCode.NEW.ordinal(), expr.loc.line(), expr.loc.column());
        chunk.write(3, expr.loc.line(), expr.loc.column());

        return null;
    }

    @Override
    public Void visitThreadExpr(ThreadExpr expr) {
        return null;
    }

    @Override
    public Void visitSelfExpr(SelfExpr expr) {
        chunk.write(OpCode.GET_LOCAL.ordinal(), expr.loc.line(), expr.loc.column());
        chunk.write(0, expr.loc.line(), expr.loc.column()); // 'self' is always at local 0 in methods
        return null;
    }

    @Override
    public Void visitSuperExpr(SuperExpr expr) {
        chunk.write(OpCode.GET_LOCAL.ordinal(), expr.loc.line(), expr.loc.column());
        chunk.write(0, expr.loc.line(), expr.loc.column()); // 'self' is always at local 0 in methods
        return null;
    }

    @Override
    public Void visitAwaitExpr(AwaitExpr expr) {
        return null;
    }

    @Override
    public Void visitUnaryExprGeneric(UnaryExpr expr) {
        return null;
    }
}