package aurora.lsp;

import aurora.compiler.antlr.AuroraLexer;
import aurora.parser.tree.*;
import aurora.parser.tree.decls.*;
import aurora.parser.tree.expr.*;
import aurora.analyzer.ModuleResolver;
import aurora.parser.tree.stmt.*;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;

import java.util.*;

/**
 * Generates semantic tokens for syntax highlighting using the ANTLR4 token stream directly.
 * AST SourceLocations can be inaccurate in some cases, so lexer tokens are used instead.
 */
public class SemanticTokenVisitor {

    public static final int TYPE_CLASS     = 0;
    public static final int TYPE_TYPE      = 1;
    public static final int TYPE_FUNCTION  = 2;
    public static final int TYPE_VARIABLE  = 3;
    public static final int TYPE_PARAMETER = 4;
    public static final int TYPE_PROPERTY  = 5;

    public static final int MOD_DECLARATION = 1;
    public static final int MOD_DEFINITION  = 1 << 1;
    public static final int MOD_READONLY    = 1 << 2;
    public static final int MOD_STATIC      = 1 << 3;

    private record SemanticToken(int line, int col, int length, int type, int modifiers) {}

    private final List<SemanticToken> tokens = new ArrayList<>();
    private final Program program;
    private final ModuleResolver modules;
    private final String source;

    /**
     * Maps "line:col" to [semanticType, modifiers] collected from the AST.
     * Used to give accurate type info for declaration sites.
     */
    private final Map<String, int[]> astHints = new HashMap<>();

    /**
     * Set of "line:col" keys for positions that are val declarations.
     * Used to suppress MOD_READONLY on references (only declarations get it).
     */
    private final Set<String> valDeclPositions = new HashSet<>();

    private SemanticTokenVisitor(Program program, ModuleResolver modules, String source) {
        this.program = program;
        this.modules = modules;
        this.source = source;
    }

    public static List<Integer> getTokens(Program program, ModuleResolver modules, String source) {
        SemanticTokenVisitor visitor = new SemanticTokenVisitor(program, modules, source);
        visitor.collectAstHints();
        visitor.processLexer();
        return visitor.build();
    }

    public static List<Integer> getTokens(Program program, ModuleResolver modules) {
        return getTokens(program, modules, null);
    }

    // -----------------------------------------------------------------------
    // Phase 1: collect hints from AST declaration nodes
    // -----------------------------------------------------------------------

    private void collectAstHints() {
        if (program == null) return;
        collectFromStatements(program.statements);
    }

    private void collectFromStatements(List<Statement> stmts) {
        if (stmts == null) return;
        for (Statement stmt : stmts) {
            collectFromStatement(stmt);
        }
    }

    private void collectFromStatement(Statement stmt) {
        if (stmt instanceof FunctionDecl d) {
            hintNameLoc(d.nameLoc, TYPE_FUNCTION, MOD_DECLARATION);
            if (d.params != null) d.params.forEach(p -> hintNameLoc(p.nameLoc, TYPE_PARAMETER, MOD_DECLARATION));
            if (d.body != null) collectFromStatements(d.body.statements);
        } else if (stmt instanceof ClassDecl d) {
            hintNameLoc(d.nameLoc, TYPE_CLASS, MOD_DECLARATION);
            if (d.members != null) d.members.forEach(this::collectFromStatement);
        } else if (stmt instanceof InterfaceDecl d) {
            hintNameLoc(d.nameLoc, TYPE_CLASS, MOD_DECLARATION);
            if (d.members != null) d.members.forEach(this::collectFromStatement);
        } else if (stmt instanceof RecordDecl d) {
            hintNameLoc(d.nameLoc, TYPE_CLASS, MOD_DECLARATION);
            if (d.parameters != null) d.parameters.forEach(p -> hintNameLoc(p.nameLoc, TYPE_PARAMETER, MOD_DECLARATION));
            if (d.members != null) d.members.forEach(this::collectFromStatement);
        } else if (stmt instanceof EnumDecl d) {
            hintNameLoc(d.nameLoc, TYPE_CLASS, MOD_DECLARATION);
        } else if (stmt instanceof FieldDecl d) {
            int mods = MOD_DECLARATION;
            if (d._static) mods |= MOD_STATIC;
            if (d.declType == FieldDecl.Type.VAL) {
                mods |= MOD_READONLY;
                if (d.nameLoc != null) {
                    valDeclPositions.add(d.nameLoc.line() + ":" + d.nameLoc.column());
                }
            }
            hintNameLoc(d.nameLoc, TYPE_VARIABLE, mods);
        } else if (stmt instanceof ConstructorDecl d) {
            if (d.params != null) d.params.forEach(p -> hintNameLoc(p.nameLoc, TYPE_PARAMETER, MOD_DECLARATION));
            if (d.body != null) collectFromStatements(d.body.statements);
        } else if (stmt instanceof ExprStmt s) {
            collectFromExpr(s.expr);
        } else if (stmt instanceof BlockStmt b) {
            collectFromStatements(b.statements);
        } else if (stmt instanceof IfStmt s) {
            collectFromExpr(s.condition);
            if (s.thenBlock != null) collectFromStatements(s.thenBlock.statements);
            if (s.elseIfs != null) s.elseIfs.forEach(ei -> {
                collectFromExpr(ei.condition);
                collectFromStatements(ei.block.statements);
            });
            if (s.elseBlock != null) collectFromStatements(s.elseBlock.statements);
        } else if (stmt instanceof LoopStmt s) {
            if (s instanceof LoopStmt.ForStmt f) collectFromExpr(f.iterable);
            else if (s instanceof LoopStmt.WhileStmt w) collectFromExpr(w.condition);
            collectFromStatements(s.body.statements);
        } else if (stmt instanceof ControlStmt.ReturnStmt r) {
            collectFromExpr(r.value);
        } else if (stmt instanceof TryStmt t) {
            collectFromStatements(t.tryBlock.statements);
            if (t.catches != null) t.catches.forEach(c -> collectFromStatements(c.block().statements));
            if (t.finallyBlock != null) collectFromStatements(t.finallyBlock.statements);
        }
    }

    private void collectFromExpr(Expr expr) {
        switch (expr) {
            case null -> {}
            case CallExpr e -> {
                if (e.callee instanceof AccessExpr access) {
                    if (access.object == null) {
                        String name = access.member;
                        if (name != null && !name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
                            hintNameLoc(access.memberLoc != null ? access.memberLoc : access.loc, TYPE_CLASS, 0);
                        } else {
                            hintNameLoc(access.memberLoc != null ? access.memberLoc : access.loc, TYPE_FUNCTION, 0);
                        }
                    } else if (access.memberLoc != null) {
                        hintNameLoc(access.memberLoc, TYPE_FUNCTION, 0);
                    }
                }
                e.arguments.forEach(arg -> collectFromExpr(arg.value));
            }
            case AccessExpr e -> {
                if (e.object != null) {
                    collectFromExpr(e.object);
                    if (e.memberLoc != null) hintNameLoc(e.memberLoc, TYPE_PROPERTY, 0);
                }
            }
            case BinaryExpr e -> {
                collectFromExpr(e.left);
                collectFromExpr(e.right);
            }
            case UnaryExpr e -> collectFromExpr(e.operand);
            case IfExpr e -> {
                collectFromExpr(e.condition);
                if (e.thenBlock != null) collectFromStatements(e.thenBlock.statements);
                if (e.elseIfs != null) e.elseIfs.forEach(ei -> {
                    collectFromExpr(ei.condition);
                    collectFromStatements(ei.block.statements);
                });
                if (e.elseBlock != null) collectFromStatements(e.elseBlock.statements);
            }
            case ElvisExpr e -> {
                collectFromExpr(e.left);
                collectFromExpr(e.right);
            }
            case LambdaExpr e -> {
                if (e.params != null) e.params.forEach(p -> hintNameLoc(p.nameLoc, TYPE_PARAMETER, MOD_DECLARATION));
                if (e.body instanceof Statement s) collectFromStatement(s);
                else if (e.body instanceof Expr ex) collectFromExpr(ex);
            }
            case ArrayExpr e -> e.elements.forEach(this::collectFromExpr);
            case IndexExpr e -> {
                collectFromExpr(e.object);
                collectFromExpr(e.index);
            }
            case CastExpr e -> collectFromExpr(e.expr);
            case TypeCheckExpr e -> collectFromExpr(e.check);
            case MatchExpr e -> collectFromExpr(e.expression);
            case AwaitExpr e -> collectFromExpr(e.expr);
            default -> {}
        }
    }

    private void hintNameLoc(aurora.parser.SourceLocation loc, int type, int modifiers) {
        if (loc == null) return;
        String key = loc.line() + ":" + loc.column();
        astHints.put(key, new int[]{type, modifiers});
    }

    // -----------------------------------------------------------------------
    // Phase 2: walk lexer tokens and classify IDENTIFIERs
    // -----------------------------------------------------------------------

    private void processLexer() {
        if (source == null) return;

        AuroraLexer lexer = new AuroraLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();

        List<Token> allTokens = new ArrayList<>();
        Token t;
        while ((t = lexer.nextToken()).getType() != Token.EOF) {
            allTokens.add(t);
        }

        for (int i = 0; i < allTokens.size(); i++) {
            if (allTokens.get(i).getType() == AuroraLexer.IDENTIFIER) {
                classifyIdentifier(allTokens.get(i), allTokens, i);
            }
        }
    }

    private void classifyIdentifier(Token tok, List<Token> allTokens, int i) {
        int line   = tok.getLine();
        int col    = tok.getCharPositionInLine();
        String text = tok.getText();
        int length  = text.length();

        // AST hints take priority
        String key = line + ":" + col;
        if (astHints.containsKey(key)) {
            int[] hint = astHints.get(key);
            tokens.add(new SemanticToken(line - 1, col, length, hint[0], hint[1]));
            return;
        }

        Token prev = prevMeaningful(allTokens, i);
        Token next = nextMeaningful(allTokens, i);

        int prevType = prev != null ? prev.getType() : -1;
        int nextType = next != null ? next.getType() : -1;

        // declaration name after class/trait/record/enum
        if (prevType == AuroraLexer.CLASS || prevType == AuroraLexer.TRAIT
                || prevType == AuroraLexer.RECORD || prevType == AuroraLexer.ENUM) {
            tokens.add(new SemanticToken(line - 1, col, length, TYPE_CLASS, MOD_DECLARATION));
            return;
        }

        // declaration name after fun
        if (prevType == AuroraLexer.FUN) {
            tokens.add(new SemanticToken(line - 1, col, length, TYPE_FUNCTION, MOD_DECLARATION));
            return;
        }

        // skip identifier directly after constructor keyword
        if (prevType == AuroraLexer.CONSTRUCTOR) {
            return;
        }

        // namespace/use statement segments
        if (prevType == AuroraLexer.PACKAGE
                || (prevType == AuroraLexer.DOT && isInsideNamespaceStatement(allTokens, i))) {
            tokens.add(new SemanticToken(line - 1, col, length, TYPE_TYPE, 0));
            return;
        }

        if (prevType == AuroraLexer.USING
                || (prevType == AuroraLexer.DOT && isInsideUseStatement(allTokens, i))) {
            tokens.add(new SemanticToken(line - 1, col, length, TYPE_TYPE, 0));
            return;
        }

        // type annotation after colon (uppercase = type name, lowercase = param name)
        if (prevType == AuroraLexer.COLON && Character.isUpperCase(text.charAt(0))) {
            tokens.add(new SemanticToken(line - 1, col, length, TYPE_TYPE, 0));
            return;
        }

        // return type after ->
        if (prevType == AuroraLexer.ARROW && Character.isUpperCase(text.charAt(0))) {
            tokens.add(new SemanticToken(line - 1, col, length, TYPE_TYPE, 0));
            return;
        }

        // generic type argument after < or , when inside generic brackets
        if ((prevType == AuroraLexer.LT || prevType == AuroraLexer.COMMA)
                && isInsideGenericArgs(allTokens, i)) {
            tokens.add(new SemanticToken(line - 1, col, length, TYPE_TYPE, 0));
            return;
        }

        // member access after dot / safe-dot / static-access
        if (prevType == AuroraLexer.DOT || prevType == AuroraLexer.QUESTION_DOT
                || prevType == AuroraLexer.COLON_COLON) {
            // treat as function call if followed by ( or generic < (e.g. obj.method<T>(...))
            if (nextType == AuroraLexer.LPAREN || nextType == AuroraLexer.LT) {
                tokens.add(new SemanticToken(line - 1, col, length, TYPE_FUNCTION, 0));
            } else {
                tokens.add(new SemanticToken(line - 1, col, length, TYPE_PROPERTY, 0));
            }
            return;
        }

        // variable declaration after val (readonly)
        if (prevType == AuroraLexer.VAL) {
            tokens.add(new SemanticToken(line - 1, col, length, TYPE_VARIABLE, MOD_DECLARATION | MOD_READONLY));
            return;
        }

        // variable declaration after var
        if (prevType == AuroraLexer.VAR) {
            tokens.add(new SemanticToken(line - 1, col, length, TYPE_VARIABLE, MOD_DECLARATION));
            return;
        }

        // condition/expression context after control flow keywords → always variable
        if (prevType == AuroraLexer.IF || prevType == AuroraLexer.WHILE
                || prevType == AuroraLexer.UNTIL || prevType == AuroraLexer.RETURN
                || prevType == AuroraLexer.THROW || prevType == AuroraLexer.ELSEIF) {
            if (Character.isUpperCase(text.charAt(0))) {
                tokens.add(new SemanticToken(line - 1, col, length, TYPE_CLASS, 0));
            } else {
                tokens.add(new SemanticToken(line - 1, col, length, TYPE_VARIABLE, 0));
            }
            return;
        }

        // call expression: identifier followed by ( or generic
        if (nextType == AuroraLexer.LPAREN || nextType == AuroraLexer.LT) {
            if (Character.isUpperCase(text.charAt(0))) {
                tokens.add(new SemanticToken(line - 1, col, length, TYPE_CLASS, 0));
            } else {
                tokens.add(new SemanticToken(line - 1, col, length, TYPE_FUNCTION, 0));
            }
            return;
        }

        // uppercase identifier = type reference
        if (Character.isUpperCase(text.charAt(0))) {
            tokens.add(new SemanticToken(line - 1, col, length, TYPE_CLASS, 0));
            return;
        }

        // fallback: variable reference (no readonly modifier — declarations already handled above)
        tokens.add(new SemanticToken(line - 1, col, length, TYPE_VARIABLE, 0));
    }

    /**
     * Returns true if the token at index i is inside a generic argument list,
     * by scanning backwards for an unmatched {@code <}.
     * Bails out early when encountering expression-context tokens.
     */
    private boolean isInsideGenericArgs(List<Token> allTokens, int i) {
        int depth = 0;
        for (int j = i - 1; j >= 0; j--) {
            int t = allTokens.get(j).getType();
            if (t == AuroraLexer.GT) {
                depth++;
            } else if (t == AuroraLexer.LT) {
                if (depth == 0) return true;
                depth--;
            } else if (t == AuroraLexer.LBRACE || t == AuroraLexer.RBRACE
                    || t == AuroraLexer.SEMICOLON) {
                return false;
            } else if (t == AuroraLexer.PLUS || t == AuroraLexer.MINUS
                    || t == AuroraLexer.STAR || t == AuroraLexer.SLASH
                    || t == AuroraLexer.AND || t == AuroraLexer.OR
                    || t == AuroraLexer.ASSIGN || t == AuroraLexer.RETURN
                    || t == AuroraLexer.IF || t == AuroraLexer.WHILE
                    || t == AuroraLexer.FAT_ARROW || t == AuroraLexer.ARROW
                    || t == AuroraLexer.LPAREN || t == AuroraLexer.EQ
                    || t == AuroraLexer.NEQ || t == AuroraLexer.LE
                    || t == AuroraLexer.GE) {
                return false;
            }
        }
        return false;
    }

    /**
     * Returns true if the token at index i is part of a {@code use} import statement.
     */
    private boolean isInsideUseStatement(List<Token> allTokens, int i) {
        for (int j = i - 1; j >= 0; j--) {
            int t = allTokens.get(j).getType();
            if (t == AuroraLexer.USING) return true;
            if (t == AuroraLexer.LBRACE || t == AuroraLexer.RBRACE
                    || t == AuroraLexer.SEMICOLON) return false;
        }
        return false;
    }

    /**
     * Returns true if the token at index i is part of a {@code namespace} declaration.
     */
    private boolean isInsideNamespaceStatement(List<Token> allTokens, int i) {
        for (int j = i - 1; j >= 0; j--) {
            int t = allTokens.get(j).getType();
            if (t == AuroraLexer.PACKAGE) return true;
            if (t == AuroraLexer.LBRACE || t == AuroraLexer.RBRACE
                    || t == AuroraLexer.SEMICOLON) return false;
        }
        return false;
    }

    private Token prevMeaningful(List<Token> allTokens, int i) {
        for (int j = i - 1; j >= 0; j--) {
            if (allTokens.get(j).getChannel() == Token.DEFAULT_CHANNEL) {
                return allTokens.get(j);
            }
        }
        return null;
    }

    private Token nextMeaningful(List<Token> allTokens, int i) {
        for (int j = i + 1; j < allTokens.size(); j++) {
            if (allTokens.get(j).getChannel() == Token.DEFAULT_CHANNEL) {
                return allTokens.get(j);
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Build: encode as LSP relative delta format
    // -----------------------------------------------------------------------

    private List<Integer> build() {
        tokens.sort(Comparator.comparingInt((SemanticToken t) -> t.line)
                .thenComparingInt(t -> t.col));

        List<Integer> result = new ArrayList<>(tokens.size() * 5);
        int prevLine = 0, prevCol = 0;
        for (SemanticToken t : tokens) {
            int deltaLine = t.line - prevLine;
            int deltaCol  = deltaLine == 0 ? t.col - prevCol : t.col;
            result.add(deltaLine);
            result.add(deltaCol);
            result.add(t.length);
            result.add(t.type);
            result.add(t.modifiers);
            prevLine = t.line;
            prevCol  = t.col;
        }
        return result;
    }
}