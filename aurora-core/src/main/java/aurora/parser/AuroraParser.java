package aurora.parser;

import aurora.analyzer.ASTPostProcessor;
import aurora.analyzer.ModuleResolver;
import aurora.analyzer.TypeInferenceEngine;
import aurora.compiler.antlr.AuroraLexer;
import aurora.compiler.antlr.AuroraParser.*;
import aurora.compiler.antlr.AuroraParserBaseVisitor;
import aurora.parser.tree.*;
import aurora.parser.tree.decls.*;
import aurora.parser.tree.expr.*;
import aurora.parser.tree.stmt.*;
import aurora.parser.tree.type.GenericParameter;
import aurora.parser.tree.type.Variance;
import aurora.parser.tree.util.BinaryOperator;
import aurora.parser.tree.util.FunctionModifier;
import aurora.parser.tree.util.UnaryOperator;
import aurora.parser.tree.util.Visibility;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A visitor that transforms an ANTLR-generated parse tree into a high-level Aurora AST.
 * This class extends {@link AuroraParserBaseVisitor} and handles the mapping of
 * grammar rules to {@link Node} subclasses.
 */
public class AuroraParser extends AuroraParserBaseVisitor<Node> {
    /** The name of the source file being parsed, used for {@link SourceLocation}. */
    private final String sourceName;

    /** The token stream, used to extract hidden comments from the lexer. */
    private final CommonTokenStream tokens;

    /**
     * Initializes a new AuroraParser visitor.
     *
     * @param sourceName The name of the source file.
     * @param tokens     The token stream for comment extraction.
     */
    public AuroraParser(String sourceName, CommonTokenStream tokens) {
        this.sourceName = sourceName;
        this.tokens = tokens;
    }

    public AuroraParser(String sourceName) {
        this(sourceName, null);
    }

    public AuroraParser() {
        this("<unknown>", null);
    }

    /**
     * Parses the given Aurora source code into a {@link Program} AST.
     * Uses a {@link CompilerErrorListener} to report errors to the console.
     *
     * @param code       The source code string.
     * @param sourceName The name of the source file.
     * @return A parsed Program AST.
     * @throws SyntaxErrorException If syntax errors are detected.
     */
    public static Program parse(String code, String sourceName, ModuleResolver modules) {
        CompilerErrorListener errorListener = new CompilerErrorListener(sourceName, code);

        AuroraLexer lexer = new AuroraLexer(CharStreams.fromString(code));
        lexer.removeErrorListeners();
        lexer.addErrorListener(errorListener);

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        aurora.compiler.antlr.AuroraParser parser = new aurora.compiler.antlr.AuroraParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(errorListener);

        CompilationUnitContext tree = parser.compilationUnit();

        if (errorListener.hasErrors()) {
            throw new SyntaxErrorException(
                    "Syntax errors detected in " + sourceName + ":\n\n" + errorListener.getFormattedErrors());
        }

        AuroraParser visitor = new AuroraParser(sourceName, tokens);
        Program program = (Program) visitor.visitCompilationUnit(tree);
        ASTPostProcessor.process(program, modules);
        return program;
    }

    /**
     * Parse with a custom ANTLR error listener that collects errors instead of
     * printing them.
     * Removes the default console error listener so stderr is not polluted.
     */
    public static Program parseWithListener(String code, String sourceName, ANTLRErrorListener errorListener, ModuleResolver modules) {
        AuroraLexer lexer = new AuroraLexer(CharStreams.fromString(code));
        lexer.removeErrorListeners();
        lexer.addErrorListener(errorListener);

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        aurora.compiler.antlr.AuroraParser parser = new aurora.compiler.antlr.AuroraParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(errorListener);

        CompilationUnitContext tree = parser.compilationUnit();
        AuroraParser visitor = new AuroraParser(sourceName, tokens);
        Program program = (Program) visitor.visitCompilationUnit(tree);
        for (var pImport : program.imports) {
            modules.loadModule(pImport.path);
        }
        ASTPostProcessor.process(program, modules);
        return program;
    }

    @Override
    public Node visit(org.antlr.v4.runtime.tree.ParseTree tree) {
        if (tree == null)
            return null;
        Node result = super.visit(tree);
        if (result != null && tree instanceof ParserRuleContext) {
            attachComments(result, (ParserRuleContext) tree);
        }
        return result;
    }

    /**
     * Attaches hidden channel comments (e.g., block comments, line comments) to an AST node.
     * Comments appearing before the node are added to {@link Node#leadingComments},
     * and those appearing on the same line or immediately after are added to {@link Node#trailingComments}.
     *
     * @param node The target AST node.
     * @param ctx  The ANTLR context associated with the node.
     */
    private void attachComments(Node node, ParserRuleContext ctx) {
        if (tokens == null || node == null || ctx == null)
            return;

        Token start = ctx.getStart();
        if (start != null) {
            int tokenIndex = start.getTokenIndex();
            List<Token> leftComments = tokens.getHiddenTokensToLeft(tokenIndex, Token.HIDDEN_CHANNEL);
            if (leftComments != null) {
                for (Token t : leftComments) {
                    String comment = t.getText().trim();
                    if (!node.leadingComments.contains(comment)) {
                        node.leadingComments.add(comment);
                    }
                }
            }
        }

        Token stop = ctx.getStop();
        if (stop != null) {
            int stopIndex = stop.getTokenIndex();
            List<Token> rightComments = tokens.getHiddenTokensToRight(stopIndex, Token.HIDDEN_CHANNEL);
            if (rightComments != null) {
                for (Token t : rightComments) {
                    String comment = t.getText().trim();
                    if (!node.trailingComments.contains(comment)) {
                        node.trailingComments.add(comment);
                    }
                }
            }
        }
    }

    /**
     * Entry point for visiting the entire compilation unit (file).
     * Orchestrates the parsing of package declarations, imports, and top-level elements.
     *
     * @param ctx The compilation unit context.
     * @return A {@link Program} node.
     */
    @Override
    public Node visitCompilationUnit(CompilationUnitContext ctx) {
        Program.Package pkg = null;
        if (ctx.packageDeclaration() != null) {
            pkg = (Program.Package) visitPackageDeclaration(ctx.packageDeclaration());
        }

        List<Program.Import> imports = ctx.importDeclaration().stream()
                .map(this::visit)
                .filter(n -> n instanceof Program.Import)
                .map(n -> (Program.Import) n)
                .collect(Collectors.toList());

        List<Statement> statements = ctx.topLevelElement().stream()
                .map(this::visit)
                .filter(java.util.Objects::nonNull)
                .map(n -> {
                    switch (n) {
                        case Statement statement -> {
                            return statement;
                        }
                        case Expr expr -> {
                            return new ExprStmt(n.loc, expr);
                        }
                        default -> throw new IllegalStateException(
                                "'" + n.getClass().getSimpleName() + "' is not a valid statement.");
                    }
                })
                .collect(Collectors.toList());

        return new Program(loc(ctx), pkg, imports, statements);
    }

    // ================= Imports =================

    @Override
    public Node visitPackageDeclaration(PackageDeclarationContext ctx) {
        return new Program.Package(loc(ctx), ctx.importPath().getText());
    }

    @Override
    public Node visitSingleImport(SingleImportContext ctx) {
        String path = ctx.importPath().getText();
        String alias = ctx.IDENTIFIER() != null ? ctx.IDENTIFIER().getText() : null;
        if (alias != null) {
            return new Program.ImportAlias(loc(ctx), path, alias);
        }
        return new Program.Import(loc(ctx), path);
    }

    @Override
    public Node visitWildcardImport(WildcardImportContext ctx) {
        String path = ctx.importPath().getText();
        return new Program.ImportWildCard(loc(ctx), path);
    }

    @Override
    public Node visitMultiImport(MultiImportContext ctx) {
        String path = ctx.importPath().getText();
        List<Program.ImportMulti.Member> members = ctx.importMember().stream()
                .map(m -> {
                    String id = m.IDENTIFIER(0).getText();
                    String alias = m.IDENTIFIER().size() > 1 ? m.IDENTIFIER(1).getText() : null;
                    return new Program.ImportMulti.Member(id, alias);
                })
                .collect(Collectors.toList());
        return new Program.ImportMulti(loc(ctx), path, members);
    }

    // ================= Declarations =================

    @Override
    public Node visitFunctionDeclaration(FunctionDeclarationContext ctx) {
        String name = ctx.IDENTIFIER().getText();
        Visibility vis = getVisibility(ctx.visibility());
        List<FunctionModifier> mods = ctx.functionModifier().stream()
                .map(m -> FunctionModifier.valueOf(m.getText().toUpperCase()))
                .collect(Collectors.toList());

        List<ParamDecl> params = ctx.parameterList() != null
                ? ctx.parameterList().parameter().stream().map(this::visit).map(p -> (ParamDecl) p).toList()
                : Collections.emptyList();

        // (typeType DOT)? IDENTIFIER ... (ARROW typeType)?
        // typeType(0) = receiver type when DOT is present; last typeType = return type when ARROW is present
        TypeNode receiverType = null;
        TypeNode retType;
        if (ctx.DOT() != null) {
            // Extension function: first typeType is receiver
            receiverType = (TypeNode) visit(ctx.typeType(0));
            retType = ctx.ARROW() != null ? (TypeNode) visit(ctx.typeType(1)) : new TypeNode(loc(ctx), "void");
        } else {
            // Normal function: first typeType (if any) is the return type
            retType = ctx.ARROW() != null ? (TypeNode) visit(ctx.typeType(0)) : new TypeNode(loc(ctx), "void");
        }

        List<GenericParameter> typeParams = getTypeParams(ctx.genericParameters());

        BlockStmt body = null;
        boolean isExprBody = false;
        if (ctx.functionBody() != null) {
            if (mods.contains(FunctionModifier.NATIVE)) {
                throw new RuntimeException("Native function '" + name + "' cannot have a body.");
            }
            if (ctx.functionBody().block() != null) {
                body = (BlockStmt) visit(ctx.functionBody().block());
            } else if (ctx.functionBody().expression() != null) {
                Expr expr = (Expr) visit(ctx.functionBody().expression());
                SourceLocation l = loc(ctx);
                body = new BlockStmt(l, Collections.singletonList(new ControlStmt.ReturnStmt(l, expr)));
                isExprBody = true;
            }
        }

        return new FunctionDecl(loc(ctx), name, receiverType, vis, mods, typeParams, params,
                retType, body, isExprBody);
    }

    // Helper to get types from expects clause directly
    private List<FunctionModifier> getFunctionModifiers(List<FunctionModifierContext> modifiersCtx) {
        if (modifiersCtx == null)
            return Collections.emptyList();
        return modifiersCtx.stream()
                .map(m -> FunctionModifier.valueOf(m.getText().toUpperCase()))
                .collect(Collectors.toList());
    }

    private List<TypeNode> getTypeList(TypeTypeListContext ctx) {
        if (ctx == null)
            return Collections.emptyList();
        return ctx.typeType().stream()
                .map(t -> (TypeNode) visit(t))
                .collect(Collectors.toList());
    }

    @Override
    public Node visitParameter(ParameterContext ctx) {
        String name = ctx.IDENTIFIER().getText();
        TypeNode type = ctx.typeType() != null ? (TypeNode) visit(ctx.typeType()) : new TypeNode(loc(ctx), "Any");
        Expr def = ctx.expression() != null ? (Expr) visit(ctx.expression()) : null;
        ParamDecl d = new ParamDecl(loc(ctx), name, type, def, ctx.VARARGS() != null);
        d.nameLoc = loc(ctx.IDENTIFIER());
        return d;
    }

    @Override
    public Node visitClassDeclaration(ClassDeclarationContext ctx) {
        Visibility vis = getVisibility(ctx.visibility());
        boolean isAbstract = ctx.ABSTRACT() != null;
        List<GenericParameter> typeParams = getTypeParams(ctx.genericParameters());

        TypeNode superClass = null;
        List<TypeNode> interfaces = new ArrayList<>();
        List<CallExpr.Argument> superArgs = null;

        if (ctx.inheritance() != null) {
            for (int i = 0; i < ctx.inheritance().inheritanceItem().size(); i++) {
                var item = ctx.inheritance().inheritanceItem(i);
                TypeNode type = (TypeNode) visit(item.typeType());
                if (i == 0) {
                    superClass = type;
                    if (item.LPAREN() != null) {
                        superArgs = parseArgs(item.argumentList());
                    }
                } else {
                    interfaces.add(type);
                }
            }
        }

        List<Declaration> members = new ArrayList<>(ctx.classBody().classMember().stream()
                .map(m -> (Declaration) visit(m.getChild(0)))
                .toList());

        if (ctx.classParameterList() != null) {
            List<ClassParamDecl> params = ctx.classParameterList().classParameter().stream().map(this::visit)
                    .map(p -> (ClassParamDecl) p).toList();
            List<ParamDecl> constructorParams = params.stream()
                    .map(cp -> new ParamDecl(
                            cp.loc,
                            cp.name,
                            cp.type,
                            cp.defaultValue,
                            false))
                    .toList();
            List<Statement> constructorBody = new ArrayList<>();

            // If we have a superclass with arguments in the header, add super(args) call
            if (superArgs != null) {
                constructorBody.add(new ExprStmt(loc(ctx), new CallExpr(loc(ctx), new SuperExpr(loc(ctx)),
                        superArgs)));
            }

            for (var param : params) {
                FieldDecl fieldDecl = new FieldDecl(
                        param.loc,
                        param.name,
                        null,
                        false,
                        param.isImmutable ? FieldDecl.Type.VAL : FieldDecl.Type.VAR,
                        param.type,
                        param.defaultValue);
                fieldDecl.nameLoc = param.nameLoc;
                members.add(fieldDecl);

                constructorBody.add(new ExprStmt(new SourceLocation(),
                        new BinaryExpr(
                                new SourceLocation(),
                                new AccessExpr(
                                        new SourceLocation(),
                                        new SelfExpr(new SourceLocation()),
                                        param.name,
                                        false),
                                new AccessExpr(loc(ctx), null, param.name, false),
                                BinaryOperator.ASSIGN)));
            }

            members.add(new ConstructorDecl(
                    new SourceLocation(),
                    Visibility.PUBLIC,
                    constructorParams,
                    new BlockStmt(new SourceLocation(), constructorBody)));
        }

        ClassDecl d = new ClassDecl(loc(ctx), ctx.IDENTIFIER().getText(), vis, isAbstract, typeParams, superClass,
                interfaces,
                members);
        d.nameLoc = loc(ctx.IDENTIFIER());
        return d;
    }

    @Override
    public Node visitInterfaceDeclaration(InterfaceDeclarationContext ctx) {
        Visibility vis = getVisibility(ctx.visibility());
        List<GenericParameter> typeParams = getTypeParams(ctx.genericParameters());
        List<TypeNode> interfaces = new ArrayList<>();
        if (ctx.inheritance() != null) {
            for (var item : ctx.inheritance().inheritanceItem()) {
                interfaces.add((TypeNode) visit(item.typeType()));
            }
        }
        List<Declaration> members = ctx.interfaceBody().interfaceMember().stream()
                .map(m -> (Declaration) visit(m.getChild(0))).toList();
        InterfaceDecl d = new InterfaceDecl(loc(ctx), ctx.IDENTIFIER().getText(), vis, typeParams, interfaces, members);
        d.nameLoc = loc(ctx.IDENTIFIER());
        return d;
    }

    @Override
    public Node visitRecordDeclaration(RecordDeclarationContext ctx) {
        Visibility vis = getVisibility(ctx.visibility());
        List<GenericParameter> typeParams = Collections.emptyList();
        List<ClassParamDecl> params = ctx.classParameterList() != null
                ? ctx.classParameterList().classParameter().stream().map(this::visit)
                .map(p -> (ClassParamDecl) p).toList()
                : Collections.emptyList();
        List<TypeNode> interfaces = new ArrayList<>();
        if (ctx.inheritance() != null) {
            for (var item : ctx.inheritance().inheritanceItem()) {
                interfaces.add((TypeNode) visit(item.typeType()));
            }
        }
        List<Declaration> members = ctx.recordMember().stream()
                .map(m -> (Declaration) visit(m.getChild(0))).toList();
        RecordDecl d = new RecordDecl(loc(ctx), ctx.IDENTIFIER().getText(), vis, typeParams, params, interfaces, members);
        d.nameLoc = loc(ctx.IDENTIFIER());
        return d;
    }

    @Override
    public ClassParamDecl visitClassParameter(ClassParameterContext ctx) {
        String name = ctx.IDENTIFIER().getText();
        TypeNode type = (TypeNode) visit(ctx.typeType());
        Expr def = ctx.expression() != null ? (Expr) visit(ctx.expression()) : null;
        ClassParamDecl d = new ClassParamDecl(loc(ctx), name, type, def, ctx.VAL() != null);
        d.nameLoc = loc(ctx.IDENTIFIER());
        return d;
    }

    @Override
    public Node visitEnumDeclaration(EnumDeclarationContext ctx) {
        Visibility vis = getVisibility(ctx.visibility());
        List<EnumDecl.EnumMember> members = ctx.enumMember().stream().map(c -> {
            String name = c.IDENTIFIER().getText();
            Expr value = c.expression() != null ? (Expr) visit(c.expression()) : null;
            List<Declaration> bodyMembers = Collections.emptyList();
            if (c.enumMemberBody() != null) {
                bodyMembers = c.enumMemberBody().children.stream()
                        .map(this::visit)
                        .filter(n -> n instanceof Declaration)
                        .map(n -> (Declaration) n)
                        .toList();
            }
            return new EnumDecl.EnumMember(loc(c), name, value, bodyMembers);
        }).toList();

        EnumDecl d = new EnumDecl(loc(ctx), ctx.IDENTIFIER().getText(), vis, members);
        d.nameLoc = loc(ctx.IDENTIFIER());
        return d;
    }

    @Override
    public Node visitThreadDeclaration(ThreadDeclarationContext ctx) {
        String name = ctx.IDENTIFIER().getText();
        List<ParamDecl> params = ctx.parameterList() != null
                ? ctx.parameterList().parameter().stream().map(this::visit).map(p -> (ParamDecl) p).toList()
                : Collections.emptyList();
        BlockStmt body = (BlockStmt) visit(ctx.block());
        return new ThreadDecl(loc(ctx), name, params, body);
    }

    @Override
    public Node visitFieldDeclaration(FieldDeclarationContext ctx) {
        Visibility vis = getVisibility(ctx.visibility());
        boolean isStatic = ctx.STATIC() != null;
        FieldDecl.Type declType = ctx.VAL() != null ? FieldDecl.Type.VAL : FieldDecl.Type.VAR;

        // Handle first variable declaration
        VariableDeclarationContext v = ctx.variableDeclaration(0);
        String name = v.IDENTIFIER().getText();
        TypeNode type = v.typeType() != null ? (TypeNode) visit(v.typeType()) : null;
        Expr init = v.expression() != null ? (Expr) visit(v.expression()) : null;

        FieldDecl d = new FieldDecl(loc(ctx), name, vis, isStatic, declType, type, init);
        d.nameLoc = loc(v.IDENTIFIER());
        return d;
    }

    // ----- Constructor Declaration -----
    @Override
    public Node visitConstructorDeclaration(ConstructorDeclarationContext ctx) {
        Visibility vis = getVisibility(ctx.visibility());
        List<ParamDecl> params = ctx.parameterList() != null
                ? ctx.parameterList().parameter().stream().map(this::visit).map(p -> (ParamDecl) p).toList()
                : Collections.emptyList();
        BlockStmt body = (BlockStmt) visit(ctx.block());
        return new ConstructorDecl(loc(ctx), vis, params, body);
    }

    // ----- Initializer Block -----
    @Override
    public Node visitInitializerBlock(InitializerBlockContext ctx) {
        BlockStmt body = (BlockStmt) visit(ctx.block());
        boolean isStatic = ctx.STATIC() != null;
        return new InitializerBlock(loc(ctx), body, isStatic);
    }

    // ----- Method Signature (for traits) -----
    @Override
    public Node visitMethodSignature(MethodSignatureContext ctx) {
        Visibility vis = getVisibility(ctx.visibility());
        boolean isAsync = ctx.ASYNC() != null;
        String name = ctx.IDENTIFIER().getText();
        List<GenericParameter> typeParams = getTypeParams(ctx.genericParameters());
        List<ParamDecl> params = ctx.parameterList() != null
                ? ctx.parameterList().parameter().stream().map(this::visit).map(p -> (ParamDecl) p).toList()
                : Collections.emptyList();
        TypeNode retType = ctx.ARROW() != null ? (TypeNode) visit(ctx.typeType(ctx.DOT() != null ? 1 : 0)) : new TypeNode(loc(ctx), "Void");

        return new MethodSignature(loc(ctx), name, vis, isAsync,
                typeParams.stream().map(param -> param.name).toList(), params, retType);
    }

    // ================= Statements =================

    @Override
    public Node visitBlockStmt(BlockStmtContext ctx) {
        return visitBlock(ctx.block());
    }

    // ----- Labeled Statement -----
    @Override
    public Node visitLabeledStmt(LabeledStmtContext ctx) {
        String label = ctx.label().IDENTIFIER().getText();
        Statement stmt = (Statement) visit(ctx.statement());
        return new LabeledStmt(loc(ctx), label, stmt);
    }

    // ----- Expression Statement -----
    @Override
    public Node visitExprStmt(ExprStmtContext ctx) {
        Expr expr = (Expr) visit(ctx.expression());
        return new ExprStmt(loc(ctx), expr);
    }

    @Override
    public Node visitBlock(BlockContext ctx) {
        List<Statement> stmts = ctx.statement().stream()
                .map(s -> {
                    Node node = visit(s);
                    if (node instanceof Statement)
                        return (Statement) node;
                    if (node instanceof Expr)
                        return new ExprStmt(node.loc, (Expr) node);
                    throw new IllegalStateException("...");
                })
                .collect(Collectors.toList());

        return new BlockStmt(loc(ctx), stmts);
    }

    @Override
    public Node visitExprBlock(ExprBlockContext ctx) {
        if (ctx.block() != null) {
            return visitBlock(ctx.block());
        } else {
            List<Statement> stmts = new ArrayList<>();
            stmts.add(new ControlStmt.ReturnStmt(
                    loc(ctx),
                    (Expr) visit(ctx.expression())
            ));
            return new BlockStmt(loc(ctx), stmts);
        }
    }

    @Override
    public Node visitLocalVarDecl(LocalVarDeclContext ctx) {
        List<Statement> decls = new ArrayList<>();
        FieldDecl.Type fieldType = ctx.VAL() != null ? FieldDecl.Type.VAL : FieldDecl.Type.VAR;
        for (VariableDeclarationContext v : ctx.variableDeclaration()) {
            String name = v.IDENTIFIER().getText();
            TypeNode type = v.typeType() != null ? (TypeNode) visit(v.typeType()) : null;
            Expr init = v.expression() != null ? (Expr) visit(v.expression()) : null;
            FieldDecl d = new FieldDecl(loc(v), name, null, false, fieldType, type, init);
            d.nameLoc = loc(v.IDENTIFIER());
            decls.add(d);
        }
        if (decls.size() == 1)
            return decls.getFirst();
        return new BlockStmt(loc(ctx), decls);
    }

    @Override
    public Node visitIfStmt(IfStmtContext ctx) {
        Expr cond = (Expr) visit(ctx.expression());
        BlockStmt then = (BlockStmt) visit(ctx.exprBlock());
        BlockStmt elseBlock = ctx.elseClause() != null ? (BlockStmt) visit(ctx.elseClause().exprBlock()) : null;

        List<IfStmt.ElseIf> elseIfs = ctx.elseifClause().stream()
                .map(e -> new IfStmt.ElseIf(loc(e), (Expr) visit(e.expression()), (BlockStmt) visit(e.exprBlock())))
                .toList();

        return new IfStmt(loc(ctx), cond, then, elseIfs, elseBlock);
    }

    @Override
    public Node visitReturnStmt(ReturnStmtContext ctx) {
        return new ControlStmt.ReturnStmt(loc(ctx), ctx.expression() != null ? (Expr) visit(ctx.expression()) : null);
    }

    @Override
    public Node visitMatchStmt(MatchStmtContext ctx) {
        Expr expr = (Expr) visit(ctx.expression());
        List<MatchStmt.MatchCase> cases = ctx.matchCase().stream()
                .map(this::visit)
                .filter(n -> n instanceof MatchStmt.MatchCase)
                .map(n -> (MatchStmt.MatchCase) n)
                .collect(Collectors.toList());
        return new MatchStmt(loc(ctx), expr, cases);
    }

    @Override
    public Node visitGuardedMatchCase(GuardedMatchCaseContext ctx) {
        return new MatchStmt.MatchCase(loc(ctx),
                (MatchStmt.Pattern) visit(ctx.patternList()),
                (Expr) visit(ctx.expression()),
                parseMatchBody(ctx.matchCaseBody()));
    }

    @Override
    public Node visitNormalCase(NormalCaseContext ctx) {
        return new MatchStmt.MatchCase(loc(ctx),
                (MatchStmt.Pattern) visit(ctx.patternList()),
                null,
                parseMatchBody(ctx.matchCaseBody()));
    }

    @Override
    public Node visitDefaultCase(DefaultCaseContext ctx) {
        return new MatchStmt.MatchCase(loc(ctx),
                new MatchStmt.DefaultPattern(loc(ctx)),
                null,
                parseMatchBody(ctx.matchCaseBody()));
    }

    @Override
    public Node visitPatternList(PatternListContext ctx) {
        if (ctx.pattern().size() == 1) {
            return visit(ctx.pattern(0));
        }

        List<MatchStmt.Pattern> patterns = ctx.pattern().stream()
                .map(this::visit)
                .map(n -> (MatchStmt.Pattern) n)
                .collect(Collectors.toList());

        // Return a MultiPattern (we may need to introduce this to the AST
        // representation if multiple patterns aren't supported natively)
        // Or if MatchCase accepts a list, we need to adapt MatchCase.
        // Looking at the grammar, literal(COMMA literal)* was folded into
        // LiteralPattern previously.
        // Let's create a MultiPattern to hold them dynamically.
        return new MatchStmt.MultiPattern(loc(ctx), patterns);
    }

    private Node parseMatchBody(MatchCaseBodyContext ctx) {
        if (ctx.block() != null)
            return visitBlock(ctx.block());
        return visit(ctx.expression());
    }

    @Override
    public Node visitLiteralPattern(LiteralPatternContext ctx) {
        return new MatchStmt.LiteralPattern(loc(ctx), (Expr) visit(ctx.literal()));
    }

    @Override
    public Node visitRangePattern(RangePatternContext ctx) {
        Expr start = (Expr) visit(ctx.expression(0));
        Expr end = (Expr) visit(ctx.expression(1));
        boolean inclusive = ctx.RANGE_INCL() != null;
        return new MatchStmt.RangePattern(loc(ctx), start, end, inclusive);
    }

    @Override
    public Node visitIsPattern(IsPatternContext ctx) {
        return new MatchStmt.IsPattern(loc(ctx), (TypeNode) visit(ctx.typeType()));
    }

    @Override
    public Node visitIdentifierPattern(IdentifierPatternContext ctx) {
        return new MatchStmt.IdentifierPattern(loc(ctx), ctx.IDENTIFIER().getText());
    }

    @Override
    public Node visitTypePattern(TypePatternContext ctx) {
        return new MatchStmt.IdentifierPattern(loc(ctx), ctx.typeType().getText());
    }

    @Override
    public Node visitDestructurePattern(DestructurePatternContext ctx) {
        List<String> names = ctx.IDENTIFIER().stream().map(TerminalNode::getText).collect(Collectors.toList());
        return new MatchStmt.DestructurePattern(loc(ctx), names);
    }

    @Override
    public Node visitWhileStmt(WhileStmtContext ctx) {
        return new LoopStmt.WhileStmt(loc(ctx), (Expr) visit(ctx.expression()), (BlockStmt) visit(ctx.block()));
    }

    @Override
    public Node visitRepeatUntilStmt(RepeatUntilStmtContext ctx) {
        return new LoopStmt.RepeatUntilStmt(loc(ctx), (BlockStmt) visit(ctx.block()),
                (Expr) visit(ctx.expression()));
    }

    @Override
    public Node visitForInStmt(ForInStmtContext ctx) {
        return new LoopStmt.ForStmt(loc(ctx), ctx.IDENTIFIER().getText(), (Expr) visit(ctx.expression()),
                (BlockStmt) visit(ctx.block()));
    }

    @Override
    public Node visitBreakStmt(BreakStmtContext ctx) {
        String label = ctx.labelRef() != null ? ctx.labelRef().getText() : null;
        return new ControlStmt.BreakStmt(loc(ctx), label);
    }

    @Override
    public Node visitContinueStmt(ContinueStmtContext ctx) {
        String label = ctx.labelRef() != null ? ctx.labelRef().getText() : null;
        return new ControlStmt.ContinueStmt(loc(ctx), label);
    }

    @Override
    public Node visitThrowStmt(ThrowStmtContext ctx) {
        return new ControlStmt.ThrowStmt(loc(ctx), (Expr) visit(ctx.expression()));
    }

    @Override
    public Node visitTryStmt(TryStmtContext ctx) {
        BlockStmt tryBlock = (BlockStmt) visitBlock(ctx.block());
        List<TryStmt.CatchClause> catches = ctx.catchClause().stream().map(c -> {
            TypeNode type = (TypeNode) visit(c.typeType());
            String name = c.IDENTIFIER().getText();
            BlockStmt body = (BlockStmt) visit(c.block());
            return new TryStmt.CatchClause(name, type, body);
        }).collect(Collectors.toList());
        BlockStmt finallyBlock = null;
        if (ctx.finallyClause() != null) {
            finallyBlock = (BlockStmt) visit(ctx.finallyClause().block());
        }
        return new TryStmt(loc(ctx), tryBlock, catches, finallyBlock);
    }

    @Override
    public Node visitTryFinallyStmt(TryFinallyStmtContext ctx) {
        BlockStmt tryBlock = (BlockStmt) visit(ctx.block());
        BlockStmt finallyBlock = (BlockStmt) visit(ctx.finallyClause().block());
        return new TryStmt(loc(ctx), tryBlock, Collections.emptyList(), finallyBlock);
    }

    // ================= Expressions =================

    // ----- Array Literals -----
    @Override
    public Node visitArrayLit(ArrayLitContext ctx) {
        List<Expr> elements = ctx.arrayLiteral().elementList() != null
                ? ctx.arrayLiteral().elementList().expression().stream().map(e -> (Expr) visit(e))
                .collect(Collectors.toList())
                : Collections.emptyList();
        return new ArrayExpr(loc(ctx), elements);
    }

    // ----- Logical Operators -----
    @Override
    public Node visitLogicalAnd(LogicalAndContext ctx) {
        return binary(loc(ctx), (Expr) visit(ctx.expression(0)), (Expr) visit(ctx.expression(1)), AuroraLexer.AND);
    }

    @Override
    public Node visitLogicalOr(LogicalOrContext ctx) {
        return binary(loc(ctx), (Expr) visit(ctx.expression(0)), (Expr) visit(ctx.expression(1)), AuroraLexer.OR);
    }

    // ----- Range Expressions -----
    @Override
    public Node visitRange(RangeContext ctx) {
        Expr start = (Expr) visit(ctx.expression(0));
        Expr end = (Expr) visit(ctx.expression(1));
        boolean inclusive = ctx.RANGE_INCL() != null;
        return new RangeExpr(loc(ctx), start, end, inclusive);
    }

    // ----- Elvis Operator -----
    @Override
    public Node visitElvis(ElvisContext ctx) {
        Expr left = (Expr) visit(ctx.expression(0));
        Expr right = (Expr) visit(ctx.expression(1));
        return new ElvisExpr(loc(ctx), left, right);
    }

    // ----- Unary Expressions -----
    @Override
    public Node visitUnaryExpr(UnaryExprContext ctx) {
        Expr operand = (Expr) visit(ctx.expression());
        UnaryOperator op;

        if (ctx.NOT() != null) {
            op = UnaryOperator.NOT;
        } else if (ctx.MINUS() != null) {
            op = UnaryOperator.NEGATE;
        } else { // PLUS
            op = UnaryOperator.POSITIVE;
        }

        return new UnaryExpr(loc(ctx), operand, op);
    }

    // ----- In Operator -----
    @Override
    public Node visitInExpression(InExpressionContext ctx) {
        Expr left = (Expr) visit(ctx.expression(0));
        Expr right = (Expr) visit(ctx.expression(1));
        return binary(loc(ctx), left, right, AuroraLexer.IN);
    }

    @Override
    public Node visitNonNullAssert(NonNullAssertContext ctx) {
        Expr operand = (Expr) visit(ctx.expression());
        return new UnaryExpr(loc(ctx), operand, UnaryOperator.NONNULL);
    }

    // ----- Index Access -----
    @Override
    public Node visitIndexAccess(IndexAccessContext ctx) {
        Expr object = (Expr) visit(ctx.expression(0));
        Expr index = (Expr) visit(ctx.expression(1));
        return new IndexExpr(loc(ctx), object, index);
    }

    // ----- Safe Member Access -----
    @Override
    public Node visitSafeMemberAccess(SafeMemberAccessContext ctx) {
        Expr target = (Expr) visit(ctx.expression());
        String member = ctx.IDENTIFIER().getText();
        SourceLocation mLoc = loc(ctx.IDENTIFIER());
        return new AccessExpr(loc(ctx), target, member, true, false, mLoc); // true = safe access
    }

    // ----- Static Member Access -----
    @Override
    public Node visitStaticMemberAccess(StaticMemberAccessContext ctx) {
        Expr target = (Expr) visit(ctx.expression());
        String member = ctx.IDENTIFIER().getText();
        SourceLocation mLoc = loc(ctx.IDENTIFIER());
        return new AccessExpr(loc(ctx), target, member, false, true, mLoc);
    }

    // ----- Type Casting -----
    @Override
    public Node visitAsCast(AsCastContext ctx) {
        Expr expr = (Expr) visit(ctx.expression());
        TypeNode type = (TypeNode) visit(ctx.typeType());
        return new CastExpr(loc(ctx), expr, type, false); // false = as cast (not is check)
    }

    // ----- If/Match Expression -----
    @Override
    public Node visitIfExpr(IfExprContext ctx) {
        Expr condition = (Expr) visit(ctx.expression());
        BlockStmt thenBlock = (BlockStmt) visit(ctx.exprBlock());

        List<IfStmt.ElseIf> elseIfs = ctx.elseifClause().stream()
                .map(e -> new IfStmt.ElseIf(loc(e), (Expr) visit(e.expression()), (BlockStmt) visit(e.exprBlock())))
                .toList();

        BlockStmt elseBlock = ctx.elseClause() != null ? (BlockStmt) visit(ctx.elseClause().exprBlock()) : null;

        return new IfExpr(loc(ctx), condition, thenBlock, elseIfs, elseBlock);
    }

    @Override
    public Node visitMatchExpr(MatchExprContext ctx) {
        Expr expr = (Expr) visit(ctx.expression());
        List<MatchStmt.MatchCase> cases = ctx.matchCase().stream()
                .map(this::visit)
                .filter(n -> n instanceof MatchStmt.MatchCase)
                .map(n -> (MatchStmt.MatchCase) n)
                .collect(Collectors.toList());
        return new MatchExpr(loc(ctx), expr, cases);
    }

    // ----- Self/Super Expressions -----
    @Override
    public Node visitSelfExpr(SelfExprContext ctx) {
        return new SelfExpr(loc(ctx));
    }

    @Override
    public Node visitSuperExpr(SuperExprContext ctx) {
        return new SuperExpr(loc(ctx));
    }

    @Override
    public Node visitSelfConstructorCall(SelfConstructorCallContext ctx) {
        List<CallExpr.Argument> args = parseArgs(ctx.argumentList());
        return new CallExpr(loc(ctx), new SelfExpr(loc(ctx)), args);
    }

    @Override
    public Node visitSuperConstructorCall(SuperConstructorCallContext ctx) {
        List<CallExpr.Argument> args = parseArgs(ctx.argumentList());
        return new CallExpr(loc(ctx), new SuperExpr(loc(ctx)), args);
    }

    @Override
    public Node visitAwaitExpr(AwaitExprContext ctx) {
        return new AwaitExpr(loc(ctx), (Expr) visit(ctx.expression()));
    }

    // ----- Thread Expression -----
    @Override
    public Node visitThreadExpr(ThreadExprContext ctx) {
        String name = ctx.threadExpression().IDENTIFIER().getText();
        List<ParamDecl> params = Collections.emptyList();
        // Thread expressions with parameters would be parsed from argumentList if
        // needed
        BlockStmt body = (BlockStmt) visitBlock(ctx.threadExpression().block());
        return new ThreadExpr(loc(ctx), name, params, body);
    }

    // ----- Record Construction -----
    @Override
    public Node visitRecordConstruction(RecordConstructionContext ctx) {
        // Record construction creates a new record inline - parse as record declaration
        return visit(ctx.recordDeclaration());
    }

    @Override
    public Node visitLiteralExpr(LiteralExprContext ctx) {
        return visit(ctx.literal());
    }

    @Override
    public Node visitLiteral(LiteralContext ctx) {
        if (ctx.INTEGER_LITERAL() != null)
            try {
                return new LiteralExpr(loc(ctx), parseNumber(Integer.class, ctx.getText()),
                        LiteralExpr.LiteralType.INT);
            } catch (IllegalArgumentException e) {
                return new ErrorNode(loc(ctx), "Too large number '" + ctx.getText() + "' for integer");
            }
        if (ctx.LONG_LITERAL() != null)
            return new LiteralExpr(loc(ctx), parseNumber(Long.class, ctx.getText()), LiteralExpr.LiteralType.LONG);
        if (ctx.FLOAT_LITERAL() != null)
            try {
                return new LiteralExpr(loc(ctx), parseNumber(Float.class, ctx.getText()),
                        LiteralExpr.LiteralType.FLOAT);
            } catch (IllegalArgumentException e) {
                return new ErrorNode(loc(ctx), "Too large number '" + ctx.getText() + "' for float");
            }
        if (ctx.DOUBLE_LITERAL() != null)
            return new LiteralExpr(loc(ctx), parseNumber(Double.class, ctx.getText()), LiteralExpr.LiteralType.DOUBLE);
        if (ctx.STRING_LITERAL() != null)
            return new LiteralExpr(loc(ctx), normalizeStringLiteral(ctx.getText()), LiteralExpr.LiteralType.STRING);
        if (ctx.TRUE() != null || ctx.FALSE() != null)
            return new LiteralExpr(loc(ctx), ctx.getText(), LiteralExpr.LiteralType.BOOL);
        return new LiteralExpr(loc(ctx), ctx.getText(), LiteralExpr.LiteralType.NULL);
    }

    /**
     * Parses a string literal into a {@link Number} of the specified type.
     * Handles underscores, hex (0x), binary (0b), and octal (0o) prefixes,
     * as well as type suffixes (L, F, D).
     *
     * @param clazz   The expected numeric type.
     * @param literal The raw string literal.
     * @return The parsed number.
     * @throws IllegalArgumentException If the literal is malformed or out of range.
     */
    private Number parseNumber(Class<? extends Number> clazz, String literal) {
        if (literal == null || literal.isEmpty()) {
            throw new IllegalArgumentException("Literal cannot be null or empty");
        }

        String cleaned = literal.replace("_", "");

        char lastChar = cleaned.charAt(cleaned.length() - 1);
        boolean hasSuffix = false;
        Class<? extends Number> targetClass = clazz;

        if (lastChar == 'L' || lastChar == 'l') {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
            targetClass = Long.class;
            hasSuffix = true;
        } else if (lastChar == 'F' || lastChar == 'f') {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
            targetClass = Float.class;
            hasSuffix = true;
        } else if (lastChar == 'D' || lastChar == 'd') {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
            targetClass = Double.class;
            hasSuffix = true;
        }

        if (!hasSuffix && (cleaned.contains(".") || cleaned.matches(".*[eE].*"))) {
            targetClass = Double.class;
        }

        if (targetClass == Integer.class || targetClass == Long.class) {
            long value;

            if (cleaned.startsWith("0x") || cleaned.startsWith("0X")) {
                value = Long.parseLong(cleaned.substring(2), 16);
            }

            else if (cleaned.startsWith("0b") || cleaned.startsWith("0B")) {
                value = Long.parseLong(cleaned.substring(2), 2);
            }

            else if (cleaned.startsWith("0o") || cleaned.startsWith("0O")) {
                value = Long.parseLong(cleaned.substring(2), 8);
            }

            else {
                value = Long.parseLong(cleaned);
            }

            if (targetClass == Integer.class) {
                if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Integer literal out of range: " + literal);
                }
                return (int) value;
            } else {
                return value;
            }
        }

        if (targetClass == Float.class || targetClass == Double.class) {
            double value = Double.parseDouble(cleaned);

            if (targetClass == Float.class) {
                if (value != 0.0 && (Math.abs(value) < Float.MIN_VALUE || Math.abs(value) > Float.MAX_VALUE)) {
                    throw new IllegalArgumentException("Float literal out of range: " + literal);
                }
                return (float) value;
            } else {
                return value;
            }
        }

        throw new IllegalArgumentException("Unsupported number type: " + targetClass.getName());
    }

    @Override
    public Node visitAddSub(AddSubContext ctx) {
        int token = ctx.PLUS() != null ? AuroraLexer.PLUS : AuroraLexer.MINUS;
        return binary(loc(ctx), (Expr) visit(ctx.expression(0)), (Expr) visit(ctx.expression(1)), token);
    }

    @Override
    public Node visitMulDivMod(MulDivModContext ctx) {
        int token = 0;
        if (ctx.STAR() != null)
            token = AuroraLexer.STAR;
        else if (ctx.SLASH() != null)
            token = AuroraLexer.SLASH;
        else
            token = AuroraLexer.PERCENT;
        return binary(loc(ctx), (Expr) visit(ctx.expression(0)), (Expr) visit(ctx.expression(1)), token);
    }

    @Override
    public Node visitComparison(ComparisonContext ctx) {
        int token = 0;
        if (ctx.LT() != null)
            token = AuroraLexer.LT;
        else if (ctx.GT() != null)
            token = AuroraLexer.GT;
        else if (ctx.LE() != null)
            token = AuroraLexer.LE;
        else
            token = AuroraLexer.GE;
        return binary(loc(ctx), (Expr) visit(ctx.expression(0)), (Expr) visit(ctx.expression(1)), token);
    }

    @Override
    public Node visitEquality(EqualityContext ctx) {
        int token = ctx.EQ() != null ? AuroraLexer.EQ : AuroraLexer.NEQ;
        return binary(loc(ctx), (Expr) visit(ctx.expression(0)), (Expr) visit(ctx.expression(1)), token);
    }

    @Override
    public Node visitAssignment(AssignmentContext ctx) {
        return binary(loc(ctx), (Expr) visit(ctx.expression(0)), (Expr) visit(ctx.expression(1)),
                ctx.assignmentOperator().start.getType());
    }

    @Override
    public Node visitFunctionCall(FunctionCallContext ctx) {
        Expr callee = (Expr) visit(ctx.expression());
        List<CallExpr.Argument> args = parseArgs(ctx.argumentList());
        return new CallExpr(loc(ctx), callee, args);
    }

    private List<CallExpr.Argument> parseArgs(ArgumentListContext ctx) {
        if (ctx == null)
            return Collections.emptyList();
        List<CallExpr.Argument> args = new ArrayList<>();
        for (NamedArgumentContext nac : ctx.namedArgument()) {
            if (nac instanceof VarargExpansionContext vac) {
                args.add(new CallExpr.Argument(null, true, (Expr) visit(vac.expression())));
            } else if (nac instanceof NormalArgumentContext norm) {
                String name = norm.IDENTIFIER() != null ? norm.IDENTIFIER().getText() : null;
                args.add(new CallExpr.Argument(name, false, (Expr) visit(norm.expression())));
            }
        }
        return args;
    }

    @Override
    public Node visitLambdaExpr(LambdaExprContext ctx) {
        return visit(ctx.lambdaExpression());
    }

    @Override
    public Node visitLambdaType(LambdaTypeContext ctx) {
        List<TypeNode> params = new ArrayList<>();
        TypedLambdaParametersContext paramsCtx = ctx.typedLambdaParameters();

        if (paramsCtx instanceof TypedTypedLambdaParamsContext t) {
            params = t.typeTypeList().typeType().stream().map(this::visit).map(p -> (TypeNode) p).toList();
        } else if (paramsCtx instanceof EmptyTypedLambdaParamsContext) {
            // Empty lambda params: () => ...
            params = Collections.emptyList();
        }

        TypeNode ret = (TypeNode) visitTypeType(ctx.typeType());

        return new TypeNode.Lambda(loc(ctx), params, ret);
    }

    @Override
    public Node visitLambdaExpression(LambdaExpressionContext ctx) {
        List<ParamDecl> params = new ArrayList<>();
        LambdaParametersContext paramsCtx = ctx.lambdaParameters();

        if (paramsCtx instanceof SingleLambdaParamContext s) {
            params.add(new ParamDecl(loc(ctx), s.IDENTIFIER().getText(), new TypeNode(), null, false));
        } else if (paramsCtx instanceof TypedLambdaParamsContext t) {
            params = t.parameterList().parameter().stream().map(this::visit).map(p -> (ParamDecl) p).toList();
        } else if (paramsCtx instanceof MultiLambdaParamsContext m) {
            for (TerminalNode id : m.IDENTIFIER()) {
                params.add(new ParamDecl(loc(ctx), id.getText(), new TypeNode(), null, false));
            }
        } else if (paramsCtx instanceof EmptyLambdaParamsContext) {
            // Empty lambda params: () => ...
            params = Collections.emptyList();
        }

        Node bodyNode;
        if (ctx.lambdaBody().block() != null)
            bodyNode = visitBlock(ctx.lambdaBody().block());
        else
            bodyNode = visit(ctx.lambdaBody().expression());

        Statement bodyStmt = (bodyNode instanceof Statement) ? (Statement) bodyNode
                : new ControlStmt.ReturnStmt(loc(ctx), (Expr) bodyNode);

        return new LambdaExpr(loc(ctx), params, bodyStmt);
    }

    @Override
    public Node visitObjectCreationExpr(ObjectCreationExprContext ctx) {
        return visit(ctx.objectCreation());
    }

    @Override
    public Node visitMemberAccess(MemberAccessContext ctx) {
        Expr target = (Expr) visit(ctx.expression());
        String member = ctx.IDENTIFIER().getText();
        SourceLocation mLoc = loc(ctx.IDENTIFIER());
        return new AccessExpr(loc(ctx), target, member, false, false, mLoc);
    }

    @Override
    public Node visitPrimaryExpr(PrimaryExprContext ctx) {
        return visit(ctx.getChild(0));
    }

    @Override
    public Node visitIdentifierExpr(IdentifierExprContext ctx) {
        return new AccessExpr(loc(ctx), null, ctx.IDENTIFIER().getText(), false);
    }

    @Override
    public Node visitParenExpr(ParenExprContext ctx) {
        return visit(ctx.expression());
    }

    @Override
    public Node visitTypeCheck(TypeCheckContext ctx) {
        return new TypeCheckExpr(loc(ctx), (TypeNode) visit(ctx.typeType()), (Expr) visit(ctx.expression()));
    }

    private BinaryExpr binary(SourceLocation loc, Expr left, Expr right, int tokenType) {
        BinaryOperator op = mapOp(tokenType);
        return new BinaryExpr(loc, left, right, op);
    }

    private BinaryOperator mapOp(int tokenType) {
        return switch (tokenType) {
            case AuroraLexer.PLUS -> BinaryOperator.ADD;
            case AuroraLexer.MINUS -> BinaryOperator.SUB;
            case AuroraLexer.STAR -> BinaryOperator.MUL;
            case AuroraLexer.SLASH -> BinaryOperator.DIV;
            case AuroraLexer.PERCENT -> BinaryOperator.MOD;
            case AuroraLexer.EQ -> BinaryOperator.EQ;
            case AuroraLexer.NEQ -> BinaryOperator.NEQ;
            case AuroraLexer.LT -> BinaryOperator.LT;
            case AuroraLexer.GT -> BinaryOperator.GT;
            case AuroraLexer.LE -> BinaryOperator.LE;
            case AuroraLexer.GE -> BinaryOperator.GE;
            case AuroraLexer.AND -> BinaryOperator.AND;
            case AuroraLexer.OR -> BinaryOperator.OR;
            case AuroraLexer.ASSIGN -> BinaryOperator.ASSIGN;
            case AuroraLexer.PLUS_ASSIGN -> BinaryOperator.PLUS_ASSIGN;
            case AuroraLexer.MINUS_ASSIGN -> BinaryOperator.MINUS_ASSIGN;
            case AuroraLexer.STAR_ASSIGN -> BinaryOperator.STAR_ASSIGN;
            case AuroraLexer.SLASH_ASSIGN -> BinaryOperator.SLASH_ASSIGN;
            case AuroraLexer.PERCENT_ASSIGN -> BinaryOperator.PERCENT_ASSIGN;
            case AuroraLexer.IN -> BinaryOperator.IN;
            default -> throw new IllegalArgumentException("Unknown Operator");
        };
    }

    @Override
    public Node visitTypeType(TypeTypeContext ctx) {
        TypeNode baseType = (TypeNode) visit(ctx.simpleType());

        List<TypeNode.TypeSuffix> suffixes = new ArrayList<>();
        // Handle type suffixes (nullable, array)
        for (TypeSuffixContext suffix : ctx.typeSuffix()) {
            if (suffix instanceof NullableTypeContext) {
                suffixes.add(new TypeNode.TypeSuffix.Nullable());
            } else if (suffix instanceof ArrayTypeContext) {
                suffixes.add(new TypeNode.TypeSuffix.Array());
            } else if (suffix instanceof SizedArrayTypeContext sizedCtx) {
                int size = Integer.parseInt(sizedCtx.INTEGER_LITERAL().getText());
                suffixes.add(new TypeNode.TypeSuffix.SizedArray(size));
            }
        }

        return new TypeNode(loc(ctx), baseType.nameLoc, baseType.name, baseType.typeArguments, suffixes);
    }

    @Override
    public Node visitSimpleType(SimpleTypeContext ctx) {
        String name;
        SourceLocation nameLoc;
        if (ctx.primitiveType() != null) {
            name = ctx.primitiveType().getText();
            nameLoc = loc(ctx.primitiveType());
        } else if (ctx.qualifiedName() != null) {
            name = ctx.qualifiedName().getText();
            nameLoc = loc(ctx.qualifiedName());
        } else if (ctx.lambdaType() != null) {
            return visitLambdaType(ctx.lambdaType());
        } else {
            throw new IllegalStateException("Unknown type");
        }

        List<TypeNode> typeArgs = Collections.emptyList();
        if (ctx.genericArguments() != null) {
            typeArgs = getTypeList(ctx.genericArguments().typeTypeList());
        }

        return new TypeNode(loc(ctx), nameLoc, name, typeArgs, Collections.emptyList());
    }

    @Override
    public Node visitQualifiedName(QualifiedNameContext ctx) {
        // Return a dummy node or just use it as string
        // For AST, we usually want the string but for LSP we might want the Node
        // Actually simpleType uses .getText() above, but we could return something else
        return super.visitQualifiedName(ctx);
    }

    private Visibility getVisibility(VisibilityContext ctx) {
        if (ctx == null)
            return Visibility.PUBLIC;
        if (ctx.PUB() != null)
            return Visibility.PUBLIC;
        if (ctx.PROTECTED() != null)
            return Visibility.PROTECTED;
        if (ctx.LOCAL() != null)
            return Visibility.PRIVATE;
        return Visibility.PUBLIC;
    }

    private List<GenericParameter> getTypeParams(GenericParametersContext ctx) {
        if (ctx == null)
            return Collections.emptyList();
        return ctx.genericParameter().stream().map(p -> {
            String name = p.IDENTIFIER().getText();
            Variance variance = Variance.NONE;
            if (p.IN() != null) {
                variance = Variance.IN;
            } else if (p.OUT() != null) {
                variance = Variance.OUT;
            }
            List<TypeNode> constraints = p.typeTypeList() != null ? getTypeList(p.typeTypeList()) : Collections.emptyList();
            TypeNode defaultType = p.typeType() != null ? (TypeNode) visit(p.typeType()) : null;
            return new GenericParameter(loc(p.IDENTIFIER()), name, variance, constraints, defaultType);
        }).collect(Collectors.toList());
    }

    @Override
    public Node visitErrorNode(org.antlr.v4.runtime.tree.ErrorNode node) {
        return new ErrorNode(loc(node), "Syntax error: " + node.getText());
    }

    private static String normalizeStringLiteral(String text) {
        // Triple-quoted string
        if (text.startsWith("\"\"\"")) {
            return text.substring(3, text.length() - 3);
        }

        // Normal string
        String body = text.substring(1, text.length() - 1);

        return unescape(body);
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (c != '\\') {
                sb.append(c);
                continue;
            }

            char next = s.charAt(++i);
            switch (next) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case 'u' -> {
                    String hex = s.substring(i + 1, i + 5);
                    sb.append((char) Integer.parseInt(hex, 16));
                    i += 4;
                }
                default -> throw new IllegalArgumentException("Invalid escape: \\" + next);
            }
        }

        return sb.toString();
    }

    /**
     * Maps an ANTLR {@link ParserRuleContext} to an Aurora {@link SourceLocation}.
     *
     * @param ctx The parser context.
     * @return The corresponding source location.
     */
    private SourceLocation loc(ParserRuleContext ctx) {
        int startLine = ctx.getStart().getLine();
        int startCol = ctx.getStart().getCharPositionInLine();
        int endLine = ctx.getStop().getLine();
        int endCol = ctx.getStop().getCharPositionInLine() + ctx.getStop().getText().length();

        return new SourceLocation(sourceName, startLine, startCol, endLine, endCol,
                ctx.getStart().getStartIndex(), ctx.getStop().getStopIndex() + 1);
    }

    private SourceLocation loc(TerminalNode node) {
        Token symbol = node.getSymbol();
        int startLine = symbol.getLine();
        int startCol = symbol.getCharPositionInLine();
        String text = node.getText();

        int endLine = startLine;
        int endCol = startCol;

        String[] lines = text.split("\r?\n", -1);
        if (lines.length > 1) {
            endLine += lines.length - 1;
            endCol = lines[lines.length - 1].length();
        } else {
            endCol += text.length();
        }

        return new SourceLocation(sourceName, startLine, startCol, endLine, endCol,
                symbol.getStartIndex(), symbol.getStopIndex() + 1);
    }
}