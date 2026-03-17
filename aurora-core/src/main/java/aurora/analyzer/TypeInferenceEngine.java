package aurora.analyzer;

import aurora.parser.SourceLocation;
import aurora.parser.tree.*;
import aurora.parser.tree.decls.*;
import aurora.parser.tree.expr.*;
import aurora.parser.tree.stmt.*;
import aurora.parser.tree.expr.LiteralExpr.LiteralType;
import aurora.parser.tree.util.BinaryOperator;

import java.util.*;

/**
 * Infers return types for every {@link BlockStmt} and unannotated
 * {@link FunctionDecl} in an Aurora AST.
 *
 * <h2>Why this exists</h2>
 * {@code AuroraParser.visitBlock()} currently rewrites the trailing
 * {@link ExprStmt} of <em>every</em> block into a
 * {@link aurora.parser.tree.stmt.ControlStmt.ReturnStmt}.  That breaks loop
 * bodies, which are always {@code void}: a trailing {@code Io::println(…)}
 * inside a {@code for} loop becomes {@code RETURN}, exiting the enclosing
 * function after the first iteration.
 *
 * <p>The fix is to run this engine <em>after</em> parsing (so the full AST is
 * available) and annotate each {@link BlockStmt} with its inferred
 * {@link TypeNode} via {@link BlockStmt#returnType}.  {@code visitBlock()} then
 * gates the rewrite on {@link #isVoid(TypeNode)}.
 *
 * <h2>Inference algorithm</h2>
 * The engine performs a single top-down walk with a context stack that tracks
 * the "owning context" of each block:
 *
 * <ol>
 *   <li><b>Loop body</b> ({@code for} / {@code while} / {@code repeat-until})
 *       → always {@code void}.</li>
 *   <li><b>Function body</b> with an explicit non-void return type annotation
 *       → annotate with that declared type.</li>
 *   <li><b>Function body</b> with <em>no</em> return type annotation (or
 *       {@code void} / {@code Void})
 *       → infer from the block's last statement:
 *       <ul>
 *         <li>last stmt is {@link ExprStmt}    → infer expression type</li>
 *         <li>last stmt is {@link aurora.parser.tree.stmt.ControlStmt.ReturnStmt}
 *             with a value → infer that value's type</li>
 *         <li>otherwise → {@code void}</li>
 *       </ul>
 *   </li>
 *   <li><b>All other blocks</b> (if-then, if-else, try, catch …)
 *       → same trailing-statement inference as (3).</li>
 * </ol>
 *
 * <h2>Scope / symbol table</h2>
 * A lightweight scope stack maps variable names to their {@link TypeNode}.
 * This is sufficient for the return-type use-case; full type-checking remains
 * the responsibility of {@link TypeChecker}.
 *
 * <h2>Integration with AuroraParser</h2>
 * <pre>{@code
 * // After parsing, before the compiler runs:
 * TypeInferenceEngine engine = new TypeInferenceEngine(program, moduleResolver);
 * engine.infer();
 *
 * // In AuroraParser.visitBlock():
 * if (!TypeInferenceEngine.isVoid(block.returnType)
 *         && !block.statements.isEmpty()
 *         && block.statements.getLast() instanceof ExprStmt exprStmt) {
 *     block.statements.set(block.statements.size() - 1,
 *             new ControlStmt.ReturnStmt(exprStmt.loc, exprStmt.expr));
 * }
 * }</pre>
 */
public final class TypeInferenceEngine {

    // -----------------------------------------------------------------------
    // Sentinel types
    // -----------------------------------------------------------------------

    /** Marks a block that must not produce a return value. */
    public static final TypeNode VOID =
            new TypeNode(new SourceLocation(), "void");

    /** Unknown / unresolved type – treated like {@code Any}. */
    public static final TypeNode UNKNOWN =
            new TypeNode(new SourceLocation(), "Any");

    // -----------------------------------------------------------------------
    // Context enum – what owns the current block?
    // -----------------------------------------------------------------------

    private enum BlockContext { FUNCTION, LOOP, OTHER }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final Program program;
    private final ModuleResolver modules; // may be null

    /**
     * Scope stack: each entry maps a variable / parameter name to its type.
     * Innermost scope is last.
     */
    private final Deque<Map<String, TypeNode>> scopes = new ArrayDeque<>();

    /**
     * Symbol table for top-level / globally visible declarations.
     * Populated during the first pass over {@link Program#statements}.
     */
    private final Map<String, Declaration> globals = new LinkedHashMap<>();

    /**
     * Resolved return types for functions whose {@link FunctionDecl#returnType}
     * was {@code null} or {@code void} at parse time.  Keyed by identity
     * (reference equality) because multiple functions may share the same name.
     */
    private final IdentityHashMap<FunctionDecl, TypeNode> inferredReturnTypes =
            new IdentityHashMap<>();

    /** Stack of the owning-context for each nested block. */
    private final Deque<BlockContext> contextStack = new ArrayDeque<>();

    /**
     * When inside a function body, the declared (or being-inferred) return type.
     * {@code null} while outside any function.
     */
    private TypeNode currentFunctionReturnType = null;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    public TypeInferenceEngine(Program program, ModuleResolver modules) {
        this.program = program;
        this.modules  = modules;
    }

    /** Convenience constructor without a module resolver. */
    public TypeInferenceEngine(Program program) {
        this(program, null);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Runs the inference pass over the whole program.
     * After this method returns, every {@link BlockStmt} in the AST has its
     * {@link BlockStmt#returnType} field set.
     */
    public void infer() {
        // First pass: collect all top-level declarations so forward references work.
        collectGlobals(program);
        // Second pass: full traversal.
        visitProgram(program);
    }

    /**
     * Returns the inferred return type for {@code decl}, falling back to the
     * declared {@link FunctionDecl#returnType} when inference was not needed.
     */
    public TypeNode getReturnType(FunctionDecl decl) {
        TypeNode declared = decl.returnType;
        if (declared != null && !isVoid(declared)) return declared;
        return inferredReturnTypes.getOrDefault(decl, VOID);
    }

    /**
     * Returns {@code true} when {@code type} represents a void / no-value result.
     * Both {@code "void"} and {@code "Void"} are treated as void; so is
     * {@code null}.
     */
    public static boolean isVoid(TypeNode type) {
        if (type == null) return true;
        String n = type.name;
        return n == null || n.isEmpty() || n.equalsIgnoreCase("void") || n.equals("none");
    }

    // -----------------------------------------------------------------------
    // First pass – global symbol collection
    // -----------------------------------------------------------------------

    private void collectGlobals(Program prog) {
        if (prog.statements == null) return;
        for (Statement s : prog.statements) {
            if (s instanceof Declaration d && d.name != null) {
                globals.put(d.name, d);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Program / statement dispatch
    // -----------------------------------------------------------------------

    private void visitProgram(Program prog) {
        pushScope();
        if (prog.statements != null) {
            for (Statement s : prog.statements) visitStatement(s);
        }
        popScope();
    }

    private void visitStatement(Statement stmt) {
        switch (stmt) {
            case ExprStmt s          -> inferExpr(s.expr);
            case BlockStmt s         -> visitBlock(s, BlockContext.OTHER);
            case IfStmt s            -> visitIfStmt(s);
            case FieldDecl s         -> visitFieldDecl(s);
            case FunctionDecl s      -> visitFunctionDecl(s);
            case ClassDecl s         -> visitClassDecl(s);
            case LoopStmt s          -> visitLoopStmt(s);
            case ControlStmt s       -> visitControlStmt(s);
            // EnumDecl, RecordDecl, InterfaceDecl – no interesting blocks inside
            default                  -> { /* no-op */ }
        }
    }

    // -----------------------------------------------------------------------
    // Block annotation  ← the heart of the engine
    // -----------------------------------------------------------------------

    /**
     * Visits a block, infers the type its last statement produces, and writes
     * the result into {@link BlockStmt#returnType}.
     *
     * @param block   the block to annotate
     * @param context who owns this block
     */
    private void visitBlock(BlockStmt block, BlockContext context) {
        if (block == null) return;

        contextStack.push(context);
        pushScope();

        TypeNode blockType = VOID;

        if (context == BlockContext.LOOP) {
            // Loop bodies are always void – visit children for side-effects only.
            for (Statement s : block.statements) visitStatement(s);
            blockType = VOID;

        } else {
            // Visit all statements; remember the type the last one produces.
            for (int i = 0; i < block.statements.size(); i++) {
                Statement s = block.statements.get(i);
                boolean isLast = (i == block.statements.size() - 1);

                if (isLast) {
                    blockType = typeOfStatement(s);
                } else {
                    visitStatement(s);
                }
            }
        }

        block.returnType = blockType;

        popScope();
        contextStack.pop();
    }

    /**
     * Returns the {@link TypeNode} that a statement "produces" as its value –
     * i.e. the type it would contribute if it were the last statement of a block.
     * For non-expression statements this is {@link #VOID}.
     */
    private TypeNode typeOfStatement(Statement stmt) {
        return switch (stmt) {
            case ExprStmt s -> {
                TypeNode t = inferExpr(s.expr);
                yield t != null ? t : VOID;
            }
            case ControlStmt.ReturnStmt s -> {
                TypeNode t = s.value != null ? inferExpr(s.value) : VOID;
                yield t != null ? t : VOID;
            }
            case FieldDecl s -> {
                visitFieldDecl(s);
                yield VOID;
            }
            case FunctionDecl s -> {
                visitFunctionDecl(s);
                yield VOID;
            }
            case IfStmt s -> {
                visitIfStmt(s);
                yield VOID;
            }
            case LoopStmt s -> {
                visitLoopStmt(s);
                yield VOID;
            }
            case BlockStmt s -> {
                visitBlock(s, BlockContext.OTHER);
                yield s.returnType != null ? s.returnType : VOID;
            }
            default -> {
                visitStatement(stmt);
                yield VOID;
            }
        };
    }

    // -----------------------------------------------------------------------
    // Statement visitors
    // -----------------------------------------------------------------------

    private void visitIfStmt(IfStmt stmt) {
        inferExpr(stmt.condition);
        visitBlock(stmt.thenBlock, BlockContext.OTHER);
        if (stmt.elseIfs != null) {
            for (IfStmt.ElseIf ei : stmt.elseIfs) {
                inferExpr(ei.condition);
                visitBlock(ei.block, BlockContext.OTHER);
            }
        }
        if (stmt.elseBlock != null) visitBlock(stmt.elseBlock, BlockContext.OTHER);
    }

    private void visitFieldDecl(FieldDecl decl) {
        TypeNode declaredType = decl.type;
        TypeNode initType     = decl.init != null ? inferExpr(decl.init) : VOID;
        TypeNode resolved     = (declaredType != null && !isVoid(declaredType))
                                    ? declaredType
                                    : (initType != null ? initType : UNKNOWN);
        declareVariable(decl.name, resolved);
    }

    private void visitFunctionDecl(FunctionDecl decl) {
        if (decl.body == null) return;

        // Register parameters in a fresh inner scope.
        pushScope();
        if (decl.params != null) {
            for (ParamDecl p : decl.params) {
                TypeNode pt = (p.type != null && !isVoid(p.type)) ? p.type : UNKNOWN;
                declareVariable(p.name, pt);
            }
        }

        TypeNode declared = decl.returnType;
        boolean hasExplicitNonVoid = declared != null && !isVoid(declared);

        TypeNode outer = currentFunctionReturnType;
        currentFunctionReturnType = hasExplicitNonVoid ? declared : null; // will be filled in

        // Visit body – use FUNCTION context so trailing exprs are typed, not voided.
        visitBlock(decl.body, BlockContext.FUNCTION);

        if (!hasExplicitNonVoid) {
            // Infer from the body's annotated return type.
            TypeNode inferred = decl.body.returnType;
            inferredReturnTypes.put(decl, inferred != null ? inferred : VOID);
        }

        currentFunctionReturnType = outer;
        popScope();
    }

    private void visitClassDecl(ClassDecl decl) {
        if (decl.members == null) return;
        pushScope();
        for (Declaration member : decl.members) {
            if (member instanceof FieldDecl f)    visitFieldDecl(f);
            else if (member instanceof FunctionDecl f) visitFunctionDecl(f);
        }
        popScope();
    }

    private void visitLoopStmt(LoopStmt stmt) {
        switch (stmt) {
            case LoopStmt.ForStmt s -> {
                inferExpr(s.iterable);
                pushScope();
                // Loop variable type: try to derive from iterable, default to UNKNOWN.
                TypeNode elemType = inferIterableElementType(s.iterable);
                declareVariable(s.varName, elemType);
                visitBlock(s.body, BlockContext.LOOP);
                popScope();
            }
            case LoopStmt.WhileStmt s -> {
                inferExpr(s.condition);
                visitBlock(s.body, BlockContext.LOOP);
            }
            case LoopStmt.RepeatUntilStmt s -> {
                visitBlock(s.body, BlockContext.LOOP);
                inferExpr(s.condition);
            }
            default -> { /* no-op */ }
        }
    }

    private void visitControlStmt(ControlStmt stmt) {
        if (stmt instanceof ControlStmt.ReturnStmt r && r.value != null) inferExpr(r.value);
        if (stmt instanceof ControlStmt.ThrowStmt  t) inferExpr(t.value);
    }

    // -----------------------------------------------------------------------
    // Expression type inference
    // -----------------------------------------------------------------------

    /**
     * Infers the {@link TypeNode} of an expression.
     * Returns {@link #UNKNOWN} rather than {@code null} when the type cannot
     * be determined, so callers never need a null check.
     */
    TypeNode inferExpr(Expr expr) {
        if (expr == null) return VOID;
        return switch (expr) {
            case LiteralExpr e  -> inferLiteral(e);
            case BinaryExpr  e  -> inferBinary(e);
            case UnaryExpr   e  -> inferExpr(e.operand);
            case CallExpr    e  -> inferCall(e);
            case AccessExpr  e  -> inferAccess(e);
            case IfExpr      e  -> inferIfExpr(e);
            case CastExpr    e  -> e.type != null ? e.type : UNKNOWN;
            case RangeExpr   e  -> new TypeNode(e.loc, "Range");
            case ArrayExpr   e  -> inferArray(e);
            case IndexExpr   e  -> inferIndex(e);
            case LambdaExpr  e  -> UNKNOWN; // lambda types are complex, skip
            case ElvisExpr   e  -> inferExpr(e.left);
            default             -> UNKNOWN;
        };
    }

    private TypeNode inferLiteral(LiteralExpr e) {
        return switch (e.type) {
            case INT    -> new TypeNode(e.loc, "int");
            case LONG   -> new TypeNode(e.loc, "long");
            case FLOAT  -> new TypeNode(e.loc, "float");
            case DOUBLE -> new TypeNode(e.loc, "double");
            case STRING -> new TypeNode(e.loc, "string");
            case BOOL   -> new TypeNode(e.loc, "bool");
            case NULL   -> new TypeNode(e.loc, "none");
        };
    }

    private TypeNode inferBinary(BinaryExpr e) {
        TypeNode left  = inferExpr(e.left);
        TypeNode right = inferExpr(e.right);

        return switch (e.op) {
            case ADD, SUB, MUL, DIV, MOD -> widenNumeric(left, right);
            case LT, LE,
                 GT, GE,
                 EQ, NEQ,
                 AND, OR, IN         -> new TypeNode(e.loc, "bool");
            case ASSIGN              -> right;
            default                  -> left;
        };
    }

    /**
     * Infers the return type of a call expression.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Method call on a known receiver type (looks up member in globals).</li>
     *   <li>Direct call to a named global function / constructor.</li>
     *   <li>Call to a locally scoped variable (e.g. a lambda stored in a val).</li>
     *   <li>Falls back to {@link #UNKNOWN}.</li>
     * </ol>
     */
    private TypeNode inferCall(CallExpr e) {
        // --- method / static call:  receiver.method(…)  or  Class::method(…) ---
        if (e.callee instanceof AccessExpr access && access.object != null) {
            TypeNode receiverType = inferExpr(access.object);
            Declaration memberDecl = resolveMember(receiverType.name, access.member);
            if (memberDecl instanceof FunctionDecl f) {
                return resolvedReturnType(f);
            }
            // Receiver type is unknown but we still need to visit args.
            for (CallExpr.Argument arg : e.arguments) inferExpr(arg.value);
            return UNKNOWN;
        }

        // --- simple call:  foo(…)  or  constructor  ---
        if (e.callee instanceof AccessExpr bare && bare.object == null) {
            String name = bare.member;

            // Constructor check: global name is a class / record.
            Declaration decl = resolve(name);
            if (decl instanceof ClassDecl cls) return new TypeNode(e.loc, cls.name);
            if (decl instanceof RecordDecl rd) return new TypeNode(e.loc, rd.name);

            if (decl instanceof FunctionDecl f) {
                for (CallExpr.Argument arg : e.arguments) inferExpr(arg.value);
                return resolvedReturnType(f);
            }

            // May be a locally-scoped value (lambda / function reference).
            TypeNode scopeType = resolveVariable(name);
            if (!scopeType.name.equals("Any")) {
                for (CallExpr.Argument arg : e.arguments) inferExpr(arg.value);
                return scopeType;
            }
        }

        for (CallExpr.Argument arg : e.arguments) inferExpr(arg.value);
        return UNKNOWN;
    }

    private TypeNode inferAccess(AccessExpr e) {
        if (e.object == null) {
            // Simple variable reference.
            TypeNode fromScope = resolveVariable(e.member);
            if (!fromScope.name.equals("Any")) return fromScope;
            Declaration d = resolve(e.member);
            if (d instanceof ClassDecl cls)    return new TypeNode(e.loc, cls.name);
            if (d instanceof FunctionDecl f)   return resolvedReturnType(f);
            if (d instanceof FieldDecl fld)    return fld.type != null ? fld.type : UNKNOWN;
            return UNKNOWN;
        }

        TypeNode objType = inferExpr(e.object);
        Declaration member = resolveMember(objType.name, e.member);
        if (member instanceof FieldDecl    fld) return fld.type != null ? fld.type : UNKNOWN;
        if (member instanceof FunctionDecl f)   return resolvedReturnType(f);
        return UNKNOWN;
    }

    private TypeNode inferIfExpr(IfExpr e) {
        inferExpr(e.condition);
        visitBlock(e.thenBlock, BlockContext.OTHER);
        TypeNode thenType = e.thenBlock.returnType;

        TypeNode elseType = VOID;
        if (e.elseBlock != null) {
            visitBlock(e.elseBlock, BlockContext.OTHER);
            elseType = e.elseBlock.returnType;
        }
        // Union: if both branches agree, return that; otherwise widen to UNKNOWN.
        return (thenType != null && elseType != null
                && thenType.name.equals(elseType.name))
                ? thenType : UNKNOWN;
    }

    private TypeNode inferArray(ArrayExpr e) {
        TypeNode elemType = UNKNOWN;
        if (e.elements != null && !e.elements.isEmpty()) {
            elemType = inferExpr(e.elements.getFirst());
        }
        // Return type as "elemType[]" – represented by adding an Array suffix.
        TypeNode arrType = new TypeNode(e.loc, elemType.name,
                List.of(), List.of(new TypeNode.TypeSuffix.Array()));
        return arrType;
    }

    private TypeNode inferIndex(IndexExpr e) {
        TypeNode obj = inferExpr(e.object);
        inferExpr(e.index);
        // Strip one array suffix.
        if (obj.suffixes != null && !obj.suffixes.isEmpty()) {
            List<TypeNode.TypeSuffix> rest =
                    obj.suffixes.subList(0, obj.suffixes.size() - 1);
            return new TypeNode(e.loc, obj.name, obj.typeArguments, rest);
        }
        return UNKNOWN;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Gets the return type for a function, using inferred type if the declared
     * one is void/null.
     */
    private TypeNode resolvedReturnType(FunctionDecl f) {
        TypeNode declared = f.returnType;
        if (declared != null && !isVoid(declared)) return declared;
        return inferredReturnTypes.getOrDefault(f, UNKNOWN);
    }

    /**
     * Attempts to infer the element type produced by iterating {@code expr}.
     * For range literals ({@code 1..10}) returns {@code int}; for array/list
     * access falls back to the element type; for everything else returns
     * {@link #UNKNOWN}.
     */
    private TypeNode inferIterableElementType(Expr expr) {
        if (expr instanceof RangeExpr r) return new TypeNode(r.loc, "int");
        TypeNode t = inferExpr(expr);
        // Unwrap one array suffix: string[] → string.
        if (t.suffixes != null && !t.suffixes.isEmpty()) {
            List<TypeNode.TypeSuffix> rest =
                    t.suffixes.subList(0, t.suffixes.size() - 1);
            return new TypeNode(t.loc, t.name, t.typeArguments, rest);
        }
        return UNKNOWN;
    }

    /**
     * Numeric widening: {@code int + double → double}, etc.
     * Returns {@code left} when neither operand is numeric.
     */
    private TypeNode widenNumeric(TypeNode left, TypeNode right) {
        if (left == null)  return right != null ? right : UNKNOWN;
        if (right == null) return left;
        int lRank = numericRank(left.name);
        int rRank = numericRank(right.name);
        if (lRank < 0 && rRank < 0) return left; // neither is numeric
        return lRank >= rRank ? left : right;
    }

    /** Returns the widening rank for a primitive numeric name, or -1 if not numeric. */
    private static int numericRank(String name) {
        return switch (name) {
            case "int"    -> 0;
            case "long"   -> 1;
            case "float"  -> 2;
            case "double" -> 3;
            default       -> -1;
        };
    }

    // -----------------------------------------------------------------------
    // Scope management
    // -----------------------------------------------------------------------

    private void pushScope() {
        scopes.push(new LinkedHashMap<>());
    }

    private void popScope() {
        if (!scopes.isEmpty()) scopes.pop();
    }

    private void declareVariable(String name, TypeNode type) {
        if (name == null || scopes.isEmpty()) return;
        scopes.peek().put(name, type != null ? type : UNKNOWN);
    }

    /**
     * Looks up a variable name in the scope stack (inner → outer).
     * Returns {@link #UNKNOWN} (with name {@code "Any"}) when not found,
     * matching the convention used by {@link TypeChecker}.
     */
    private TypeNode resolveVariable(String name) {
        for (Map<String, TypeNode> scope : scopes) {
            TypeNode t = scope.get(name);
            if (t != null) return t;
        }
        return UNKNOWN;
    }

    /**
     * Resolves a simple (unqualified) name against the global declaration table.
     * Falls back to {@code null} when not found.
     */
    private Declaration resolve(String name) {
        Declaration d = globals.get(name);
        if (d != null) return d;
        // Try module resolver for imported names.
        if (modules != null) {
            return modules.resolveFromImports(program, name);
        }
        return null;
    }

    /**
     * Looks up a member (field or method) inside the class / record named
     * {@code typeName}.  Returns {@code null} when not found.
     */
    private Declaration resolveMember(String typeName, String memberName) {
        if (typeName == null || memberName == null) return null;
        Declaration typeDecl = resolve(typeName);
        if (typeDecl instanceof ClassDecl cls && cls.members != null) {
            for (Declaration m : cls.members) {
                if (memberName.equals(m.name)) return m;
            }
        }
        if (typeDecl instanceof RecordDecl rd && rd.members != null) {
            for (Declaration m : rd.members) {
                if (memberName.equals(m.name)) return m;
            }
        }
        return null;
    }
}