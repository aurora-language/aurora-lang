package aurora.analyzer;

import aurora.parser.SourceLocation;
import aurora.parser.tree.*;
import aurora.parser.tree.decls.*;
import aurora.parser.tree.expr.*;
import aurora.parser.tree.stmt.*;

import java.util.*;

/**
 * Infers the return types of all {@link BlockStmt} nodes in an AST.
 *
 * <p>This engine performs a two-pass analysis:
 * <ol>
 *   <li>Global symbol collection (forward-reference support, import resolution)</li>
 *   <li>Full AST traversal with recursive-safe type inference</li>
 * </ol>
 *
 * <h2>Recursive function handling</h2>
 * When a function calls itself (directly or mutually), its return type is not yet
 * known at the time of the recursive call.  The engine handles this with an
 * "in-progress" sentinel: during inference of a function body, the function's
 * return type is set to {@link #IN_PROGRESS}.  Any recursive call that encounters
 * {@code IN_PROGRESS} falls back to skipping the recursive branch.  After the body
 * is fully visited the final inferred type replaces the sentinel, and a second pass
 * re-evaluates the body with the now-known return type so that callers get the
 * correct type rather than {@code UNKNOWN}.
 *
 * <h2>Binary-expression type merging</h2>
 * For binary expressions such as {@code fib(n-1) + fib(n-2)}, both operands are
 * inferred and merged: if one side is {@code UNKNOWN} / {@code Any} and the other
 * is concrete, the concrete type wins.  If both sides remain {@code UNKNOWN} after
 * all resolution attempts, a diagnostic is emitted and the expression type stays
 * {@code UNKNOWN} (which will later block compilation).
 *
 * <h2>Diagnostics</h2>
 * Diagnostics (errors/warnings) are accumulated in a list accessible via
 * {@link #getDiagnostics()}.  Callers should propagate these to the user.
 *
 * <p>This is sufficient for the return-type use-case; full type-checking remains
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

    /**
     * Sentinel used while a function body is being inferred to detect recursive
     * calls and avoid infinite recursion.
     */
    private static final TypeNode IN_PROGRESS =
            new TypeNode(new SourceLocation(), "__in_progress__");

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
     *
     * <p>During inference of a function body, the entry for that function is
     * temporarily set to {@link #IN_PROGRESS} to detect recursive calls.
     */
    private final IdentityHashMap<FunctionDecl, TypeNode> inferredReturnTypes =
            new IdentityHashMap<>();

    /**
     * When inside a function body, the declared (or being-inferred) return type.
     * {@code null} while outside any function.
     */
    private TypeNode currentFunctionReturnType = null;

    /**
     * Diagnostics accumulated during inference.
     * Callers retrieve these via {@link #getDiagnostics()}.
     */
    private final List<AuroraDiagnostic> diagnostics = new ArrayList<>();

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
        // First pass: collect all top-level declarations so forward references work,
        // including symbols imported from other modules.
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
     * Returns the list of diagnostics emitted during inference.
     * This includes errors for expressions whose type could not be determined.
     */
    public List<AuroraDiagnostic> getDiagnostics() {
        return Collections.unmodifiableList(diagnostics);
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

    /**
     * Collects all top-level declarations into {@link #globals}, including
     * declarations exported from imported modules.
     *
     * <p>Import resolution: each {@code use} directive is resolved through the
     * {@link ModuleResolver}.  Every top-level {@link Declaration} found in the
     * imported module is added to {@code globals} under its unqualified name so
     * that code such as {@code Io::println(…)} can be resolved to the correct
     * {@link ClassDecl} or {@link FunctionDecl}.
     */
    private void collectGlobals(Program prog) {
        if (prog.statements == null) return;

        // 1. Local top-level declarations
        for (Statement s : prog.statements) {
            if (s instanceof Declaration d && d.name != null) {
                globals.put(d.name, d);
            }
        }

        // 2. Imported module declarations
        if (modules != null && prog.imports != null) {
            for (Program.Import imp : prog.imports) {
                Program mod = modules.loadModule(imp.path);
                if (mod == null || mod.statements == null) continue;
                for (Statement s : mod.statements) {
                    if (s instanceof Declaration d && d.name != null) {
                        // Don't overwrite locally-defined names
                        globals.putIfAbsent(d.name, d);
                    }
                }
                // Also register the last segment of the import path as an alias for
                // the module's primary class (e.g. "Aurora.Io" → register "Io").
                String path = imp.path;
                if (path != null) {
                    String lastName = path.contains(".")
                            ? path.substring(path.lastIndexOf('.') + 1)
                            : path;
                    for (Statement s : mod.statements) {
                        if (s instanceof ClassDecl cls && lastName.equals(cls.name)) {
                            globals.putIfAbsent(lastName, cls);
                            break;
                        }
                    }
                }
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
     */
    private void visitBlock(BlockStmt block, BlockContext ctx) {
        pushScope();

        TypeNode last = VOID;
        if (block.statements != null) {
            for (Statement s : block.statements) {
                last = visitBlockStatement(s, ctx);
            }
        }

        // Loop bodies never produce a return value.
        block.returnType = (ctx == BlockContext.LOOP) ? VOID : last;

        popScope();
    }

    /**
     * Visits a single statement inside a block and returns the type it produces
     * as the "tail value" of that statement (VOID for most statements).
     */
    private TypeNode visitBlockStatement(Statement stmt, BlockContext ctx) {
        return switch (stmt) {
            case ExprStmt s -> {
                TypeNode t = inferExpr(s.expr);
                yield (ctx == BlockContext.FUNCTION) ? t : VOID;
            }
            case ControlStmt.ReturnStmt s -> {
                TypeNode t = (s.value != null) ? inferExpr(s.value) : VOID;
                yield t;
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

    /**
     * Infers the return type of a function declaration.
     *
     * <h3>Recursive-safe inference algorithm</h3>
     * <ol>
     *   <li>Before visiting the body, register {@link #IN_PROGRESS} for this
     *       function in {@code inferredReturnTypes} so that recursive calls
     *       encounter the sentinel instead of looping.</li>
     *   <li>Visit the body to obtain a first-pass inferred type.  If a recursive
     *       call was encountered it will have returned {@code UNKNOWN}; the rest
     *       of the expression (e.g. the other branch of the {@code if}) may still
     *       resolve to a concrete type.</li>
     *   <li>Register the first-pass result and re-visit the body once more.
     *       Now that the return type is known, recursive calls resolve correctly
     *       and the final inferred type is stable.</li>
     * </ol>
     */
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

        if (hasExplicitNonVoid) {
            currentFunctionReturnType = declared;
            // Visit body – use FUNCTION context so trailing exprs are typed, not voided.
            visitBlock(decl.body, BlockContext.FUNCTION);
        } else {
            // Mark as in-progress before visiting to handle recursive self-calls.
            inferredReturnTypes.put(decl, IN_PROGRESS);
            currentFunctionReturnType = null;

            // First pass: body is visited; recursive calls see IN_PROGRESS → UNKNOWN.
            visitBlock(decl.body, BlockContext.FUNCTION);
            TypeNode firstPass = decl.body.returnType;
            TypeNode resolved  = (firstPass != null && !isInProgress(firstPass))
                    ? firstPass : UNKNOWN;

            // Register first-pass result so that recursive calls in the second pass
            // can use the (now-known) concrete type.
            inferredReturnTypes.put(decl, resolved);

            // Second pass: only needed when first pass produced UNKNOWN (recursive case).
            if (isUnknown(resolved)) {
                visitBlock(decl.body, BlockContext.FUNCTION);
                TypeNode secondPass = decl.body.returnType;
                if (secondPass != null && !isUnknown(secondPass) && !isInProgress(secondPass)) {
                    resolved = secondPass;
                }
            }

            inferredReturnTypes.put(decl, resolved != null ? resolved : VOID);
            decl.returnType = resolved;
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
            case LambdaExpr  e  -> UNKNOWN; // lambda types are complex, so currently skip processing
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

    /**
     * Infers the type of a binary expression.
     *
     * <h3>Type merging for UNKNOWN operands</h3>
     * After inferring both sides, if one side is {@code UNKNOWN} / {@code Any} and
     * the other is a concrete type, the concrete type is used (e.g. during the
     * second pass of recursive inference, {@code fib(n-1) + fib(n-2)} resolves
     * correctly once the function's return type is known).
     *
     * If both sides are {@code UNKNOWN} after all resolution attempts, a
     * diagnostic error is emitted so the problem is visible to the user.
     */
    private TypeNode inferBinary(BinaryExpr e) {
        TypeNode left  = inferExpr(e.left);
        TypeNode right = inferExpr(e.right);

        return switch (e.op) {
            case ADD, SUB, MUL, DIV, MOD -> {
                TypeNode merged = mergeTypes(e, left, right);
                yield widenNumeric(merged, right);
            }
            case LT, LE,
                 GT, GE,
                 EQ, NEQ,
                 AND, OR, IN         -> new TypeNode(e.loc, "bool");
            case ASSIGN              -> right;
            default                  -> left;
        };
    }

    /**
     * Merges two types for arithmetic expressions, preferring concrete types over
     * {@code UNKNOWN}.  Emits a diagnostic when both sides are unresolvable.
     *
     * @param anchor the expression node used for diagnostic location
     * @param left   inferred type of the left operand
     * @param right  inferred type of the right operand
     * @return the best concrete type, or {@code UNKNOWN} if neither side is concrete
     */
    private TypeNode mergeTypes(Expr anchor, TypeNode left, TypeNode right) {
        boolean leftUnknown  = isUnknown(left);
        boolean rightUnknown = isUnknown(right);

        if (!leftUnknown)  return left;
        if (!rightUnknown) return right;

        // Both sides are UNKNOWN – emit a diagnostic error.
        diagnostics.add(AuroraDiagnostic.error(
                anchor.loc,
                "Cannot determine the type of this expression: both operands have unknown types. Please add an explicit type annotation.",
                "Aurora TypeInferenceEngine")
        );
        return UNKNOWN;
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
     *
     * <p>When the resolved function's return type is {@link #IN_PROGRESS} (i.e. the
     * function is currently being inferred – a recursive call), {@code UNKNOWN} is
     * returned for this invocation so that the caller can still determine its own
     * type from the non-recursive branch.
     */
    private TypeNode inferCall(CallExpr e) {
        // --- method / static call:  receiver.method(…)  or  Class::method(…) ---
        if (e.callee instanceof AccessExpr access && access.object != null) {
            TypeNode receiverType = inferExpr(access.object);
            Declaration memberDecl = resolveMember(receiverType.name, access.member);
            if (memberDecl instanceof FunctionDecl f) {
                TypeNode ret = resolvedReturnType(f);
                if (isInProgress(ret)) {
                    for (CallExpr.Argument arg : e.arguments) inferExpr(arg.value);
                    return UNKNOWN; // recursive call – type not yet known
                }
                for (CallExpr.Argument arg : e.arguments) inferExpr(arg.value);
                return ret;
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
                TypeNode ret = resolvedReturnType(f);
                for (CallExpr.Argument arg : e.arguments) inferExpr(arg.value);
                if (isInProgress(ret)) {
                    return UNKNOWN; // recursive call – type not yet known
                }
                return ret;
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

    /**
     * Infers the type of an if-expression.
     *
     * <p>The result type is determined by merging the then-branch and else-branch
     * types:
     * <ul>
     *   <li>If both branches agree on the same concrete type, that type is returned.</li>
     *   <li>If one branch is {@code UNKNOWN} / {@code Any} and the other is
     *       concrete, the concrete type is used (important during recursive
     *       inference where the recursive branch initially returns {@code UNKNOWN}
     *       but the base-case branch returns a concrete type such as {@code int}).</li>
     *   <li>If only a then-branch exists and it has a concrete type, that type
     *       is returned (single-arm if-expression).</li>
     * </ul>
     */
    private TypeNode inferIfExpr(IfExpr e) {
        inferExpr(e.condition);
        visitBlock(e.thenBlock, BlockContext.OTHER);
        TypeNode thenType = e.thenBlock.returnType;

        TypeNode elseType = VOID;
        if (e.elseBlock != null) {
            visitBlock(e.elseBlock, BlockContext.OTHER);
            elseType = e.elseBlock.returnType;
        }

        // If one branch produced a concrete type and the other is UNKNOWN/void,
        // prefer the concrete type.  This handles the recursive base-case pattern:
        //   if n < 2 then n          ← base case: concrete (int)
        //   else fib(n-1) + fib(n-2) ← recursive branch: UNKNOWN on first pass
        if (!isVoid(thenType) && !isUnknown(thenType)) {
            if (isVoid(elseType) || isUnknown(elseType)) return thenType;
        }
        if (!isVoid(elseType) && !isUnknown(elseType)) {
            if (isVoid(thenType) || isUnknown(thenType)) return elseType;
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
     * one is void/null.  Returns {@link #IN_PROGRESS} if the function is
     * currently being inferred (to indicate a recursive call).
     */
    private TypeNode resolvedReturnType(FunctionDecl f) {
        TypeNode declared = f.returnType;
        if (declared != null && !isVoid(declared)) return declared;
        TypeNode inferred = inferredReturnTypes.get(f);
        // IN_PROGRESS → signal recursive call to caller
        if (inferred != null) return inferred;
        return UNKNOWN;
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
    static int numericRank(String name) {
        if (name == null) return -1;
        return switch (name) {
            case "int"    -> 0;
            case "long"   -> 1;
            case "float"  -> 2;
            case "double" -> 3;
            default       -> -1;
        };
    }

    /** Returns {@code true} when {@code type} is the {@link #UNKNOWN} / "Any" sentinel. */
    private static boolean isUnknown(TypeNode type) {
        if (type == null) return true;
        return "Any".equals(type.name) || type == UNKNOWN;
    }

    /** Returns {@code true} when {@code type} is the {@link #IN_PROGRESS} sentinel. */
    private static boolean isInProgress(TypeNode type) {
        return type != null && IN_PROGRESS.name.equals(type.name);
    }

    // -----------------------------------------------------------------------
    // Scope management
    // -----------------------------------------------------------------------

    private void pushScope() { scopes.push(new LinkedHashMap<>()); }
    private void popScope()  { if (!scopes.isEmpty()) scopes.pop(); }

    private void declareVariable(String name, TypeNode type) {
        if (name == null || scopes.isEmpty()) return;
        scopes.peek().put(name, type != null ? type : UNKNOWN);
    }

    private TypeNode resolveVariable(String name) {
        for (TypeNode t : scopes.stream()
                .map(m -> m.get(name))
                .filter(Objects::nonNull)
                .toList()) {
            return t;
        }
        return UNKNOWN;
    }

    /**
     * Resolves a top-level declaration by name, first from {@link #globals},
     * then from imported modules via the {@link ModuleResolver}.
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