package aurora.lsp;

import aurora.analyzer.AnalysisResult;
import aurora.analyzer.AuroraAnalyzer;
import aurora.analyzer.AuroraDiagnostic;
import aurora.analyzer.ModuleResolver;
import aurora.analyzer.NodeFinder;
import aurora.analyzer.SymbolResolver;
import aurora.parser.tree.Declaration;
import aurora.parser.tree.Node;
import aurora.parser.tree.Program;
import aurora.parser.tree.Program.Import;
import aurora.parser.tree.decls.*;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Implementation of the {@link TextDocumentService} for Aurora.
 * Manages the lifecycle of text documents, performing on-the-fly parsing,
 * type checking, and providing IDE features like completion and hover.
 */
public class AuroraTextDocumentService implements TextDocumentService {
    /** The parent language server instance. */
    private final AuroraLanguageServer server;

    /** Sentinel value representing a verified lack of hover information for a position. */
    private static final Hover EMPTY_HOVER = new Hover(new MarkupContent(MarkupKind.MARKDOWN, ""));

    /** A map of document URIs to their parsed AST {@link Program} nodes. */
    private final Map<String, Program> astMap = new ConcurrentHashMap<>();

    /** Resolves modules and library paths for cross-file features. */
    private final ModuleResolver moduleResolver = new ModuleResolver();

    /**
     * Constructs a new AuroraTextDocumentService.
     *
     * @param server The language server instance this service belongs to.
     */
    public AuroraTextDocumentService(AuroraLanguageServer server) {
        this.server = server;
    }

    /**
     * Cache: "uri:line:col" → computed Hover (null means already checked, no
     * hover). Use sentinel.
     */
    private final Map<String, Hover> hoverCache = new ConcurrentHashMap<>();

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        AuroraLanguageServer.LOGGER.info("didOpen: " + uri);
        initProjectRoot(uri);
        hoverCache.keySet().removeIf(k -> k.startsWith(uri + ":"));
        validateDocument(uri, params.getTextDocument().getText());
    }

    /**
     * Attempts to identify the project root directory from a document URI.
     * Searches upwards for common project markers like {@code aurora/lib}, {@code pom.xml}, or {@code package.json}.
     *
     * @param uri The URI of the document to start the search from.
     */
    private void initProjectRoot(String uri) {
        if (moduleResolver.getProjectRoot() != null)
            return;
        try {
            // Derive project root from the document URI (walk up until we find aurora/lib
            // or src)
            Path docPath = Paths.get(new URI(uri.replace("%3A", ":")));
            Path candidate = docPath.getParent();
            while (candidate != null) {
                if (candidate.resolve("aurora/lib").toFile().isDirectory()
                        || candidate.resolve("pom.xml").toFile().exists()
                        || candidate.resolve("package.json").toFile().exists()) {
                    moduleResolver.setProjectRoot(candidate);
                    AuroraLanguageServer.LOGGER.info("  projectRoot set to: " + candidate);
                    return;
                }
                candidate = candidate.getParent();
            }
        } catch (Exception e) {
            AuroraLanguageServer.LOGGER.error("  initProjectRoot failed", e);
        }
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        AuroraLanguageServer.LOGGER.info("didChange: " + uri);
        hoverCache.keySet().removeIf(k -> k.startsWith(uri + ":"));
        validateDocument(uri, params.getContentChanges().get(0).getText());
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        astMap.remove(uri);
        hoverCache.keySet().removeIf(k -> k.startsWith(uri + ":"));
        server.getClient().publishDiagnostics(
                new PublishDiagnosticsParams(uri, Collections.emptyList()));
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            DefinitionParams params) {
        String uri = params.getTextDocument().getUri();
        Program program = astMap.get(uri);
        if (program == null)
            return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));

        int line = params.getPosition().getLine() + 1;
        int charPos = params.getPosition().getCharacter();

        NodeFinder finder = new NodeFinder(line, charPos);
        List<Node> path = finder.findPath(program);
        Node node = (path != null && !path.isEmpty()) ? path.get(path.size() - 1) : null;

        Node decl = SymbolResolver.resolve(node, path);

        if (decl != null && decl.loc != null) {
            Range range = new Range(
                    new Position(Math.max(0, decl.loc.line() - 1), decl.loc.column()),
                    new Position(Math.max(0, decl.loc.endLine() - 1), decl.loc.endColumn()));
            Location location = new Location(uri, range);
            return CompletableFuture.completedFuture(Either.forLeft(Collections.singletonList(location)));
        }

        return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams position) {
        String uri = position.getTextDocument().getUri();
        Program program = astMap.get(uri);
        List<CompletionItem> items = new ArrayList<>();

        // Add keywords
        String[] keywords = { "val", "var", "fun", "class", "trait", "record", "enum", "if", "else", "match",
                "while", "for", "return", "true", "false", "null", "new", "import", "package", "is", "as", "break",
                "continue", "try", "catch", "finally", "throw", "thread", "inject", "constructor", "self", "super" };
        for (String kw : keywords) {
            CompletionItem item = new CompletionItem(kw);
            item.setKind(CompletionItemKind.Keyword);
            items.add(item);
        }

        if (program != null) {
            if (program.statements != null) {
                for (aurora.parser.tree.Statement stmt : program.statements) {
                    if (stmt instanceof Declaration decl) {
                        addCompletionItem(items, decl);
                    }
                }
            }
            if (program.imports != null) {
                for (Program.Import imp : program.imports) {
                    Program importedProgram = moduleResolver.loadModule(imp.path);
                    if (importedProgram != null && importedProgram.statements != null) {
                        for (aurora.parser.tree.Statement stmt : importedProgram.statements) {
                            if (stmt instanceof Declaration decl) {
                                addCompletionItem(items, decl);
                            }
                        }
                    }
                }
            }
        }

        return CompletableFuture.completedFuture(Either.forLeft(items));
    }

    /**
     * Adds a completion item for a declaration to the provided list.
     * Sets the appropriate icon and detail string based on the declaration type.
     *
     * @param items The list of completion items to populate.
     * @param decl  The declaration to add.
     */
    private void addCompletionItem(List<CompletionItem> items, Declaration decl) {
        String name = decl.name;
        if (name == null || name.isEmpty() || name.equals("<anonymous>"))
            return;

        CompletionItem item = new CompletionItem(name);
        if (decl instanceof FunctionDecl) {
            item.setKind(CompletionItemKind.Function);
            item.setDetail("fun " + name);
        } else if (decl instanceof ClassDecl) {
            item.setKind(CompletionItemKind.Class);
            item.setDetail("class " + name);
        } else if (decl instanceof InterfaceDecl) {
            item.setKind(CompletionItemKind.Interface);
            item.setDetail("trait " + name);
        } else if (decl instanceof RecordDecl) {
            item.setKind(CompletionItemKind.Struct);
            item.setDetail("record " + name);
        } else if (decl instanceof EnumDecl) {
            item.setKind(CompletionItemKind.Enum);
            item.setDetail("enum " + name);
        } else if (decl instanceof FieldDecl field) {
            item.setKind(CompletionItemKind.Field);
            item.setDetail((field._static ? "static " : "") + field.declType.name().toLowerCase() + " " + name);
        } else {
            item.setKind(CompletionItemKind.Variable);
        }
        items.add(item);
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        String uri = params.getTextDocument().getUri();
        Program program = astMap.get(uri);

        int line = params.getPosition().getLine() + 1; // LSP 0-based → Aurora 1-based
        int charPos = params.getPosition().getCharacter();

        // Fast path: return cached result
        String cacheKey = uri + ":" + line + ":" + charPos;
        if (hoverCache.containsKey(cacheKey)) {
            Hover cached = hoverCache.get(cacheKey);
            return CompletableFuture.completedFuture(cached == EMPTY_HOVER ? null : cached);
        }

        AuroraLanguageServer.LOGGER.info("hover request: uri=%s line=%d char=%d  astMap.contains=%b",
                uri, line, charPos, program != null);

        if (program == null) {
            hoverCache.put(cacheKey, EMPTY_HOVER);
            return CompletableFuture.completedFuture(null);
        }

        NodeFinder finder = new NodeFinder(line, charPos);
        List<Node> path = finder.findPath(program);
        Node node = (path != null && !path.isEmpty()) ? path.get(path.size() - 1) : null;

        AuroraLanguageServer.LOGGER.info("  NodeFinder result: pathSize=%d  node=%s",
                path != null ? path.size() : -1,
                node != null ? node.getClass().getSimpleName() + " @ " + node.loc : "null");

        Node decl = SymbolResolver.resolve(node, path, program, moduleResolver);

        AuroraLanguageServer.LOGGER.info("  SymbolResolver result: %s",
                decl != null ? decl.getClass().getSimpleName() : "null");

        if (decl != null) {
            Hover hover = new Hover(new MarkupContent(MarkupKind.MARKDOWN, buildHoverContent(decl)));
            hoverCache.put(cacheKey, hover);
            return CompletableFuture.completedFuture(hover);
        }

        hoverCache.put(cacheKey, EMPTY_HOVER);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Builds the markdown content for a hover tooltip based on a declaration node.
     * Generates a "Aurora" code block with the declaration signature.
     *
     * @param decl The declaration to document.
     * @return A markdown string for the hover tooltip.
     */
    private String buildHoverContent(Node decl) {
        StringBuilder code = new StringBuilder();

        if (decl instanceof FunctionDecl func) {
            if (func.visibility != null)
                code.append(func.visibility).append(" ");
            if (func.modifiers != null)
                func.modifiers.forEach(m -> code.append(m).append(" "));
            code.append("fun ").append(func.name).append("(");
            if (func.params != null) {
                for (int i = 0; i < func.params.size(); i++) {
                    if (i > 0)
                        code.append(", ");
                    code.append(func.params.get(i).toString());
                }
            }
            code.append(")");
            if (func.returnType != null)
                code.append(": ").append(func.returnType);

        } else if (decl instanceof FieldDecl field) {
            if (field.visibility != null)
                code.append(field.visibility).append(" ");
            if (field._static)
                code.append("static ");
            code.append(field.declType.name().toLowerCase()).append(" ").append(field.name);
            if (field.type != null && !field.type.name.equals("none"))
                code.append(": ").append(field.type);
            else if (field.init != null)
                code.append(" = ").append(field.init); // show inferred value hint

        } else if (decl instanceof ParamDecl param) {
            code.append("(parameter) ").append(param.name);
            if (param.type != null)
                code.append(": ").append(param.type);

        } else if (decl instanceof ClassDecl cls) {
            if (cls.visibility != null)
                code.append(cls.visibility).append(" ");
            if (cls.isAbstract)
                code.append("abstract ");
            code.append("class ").append(cls.name);
            if (!cls.typeParams.isEmpty())
                code.append("<").append(cls.typeParams.stream().map(Object::toString).collect(Collectors.joining(", "))).append(">");
            if (cls.superClass != null)
                code.append(" : ").append(cls.superClass);
            // Show constructor signature(s) if available
            if (cls.members != null) {
                for (Declaration member : cls.members) {
                    if (member instanceof ConstructorDecl ctor) {
                        code.append("\n\nconstructor(");
                        if (ctor.params != null) {
                            for (int i = 0; i < ctor.params.size(); i++) {
                                if (i > 0)
                                    code.append(", ");
                                code.append(ctor.params.get(i).toString());
                            }
                        }
                        code.append(")");
                    }
                }
            }

        } else if (decl instanceof RecordDecl rec) {
            if (rec.visibility != null)
                code.append(rec.visibility).append(" ");
            code.append("record ").append(rec.name);

        } else if (decl instanceof InterfaceDecl iface) {
            if (iface.visibility != null)
                code.append(iface.visibility).append(" ");
            code.append("trait ").append(iface.name);

        } else if (decl instanceof ConstructorDecl ctor) {
            if (ctor.visibility != null)
                code.append(ctor.visibility).append(" ");
            code.append("constructor(");
            if (ctor.params != null) {
                for (int i = 0; i < ctor.params.size(); i++) {
                    if (i > 0)
                        code.append(", ");
                    code.append(ctor.params.get(i).toString());
                }
            }
            code.append(")");

        } else if (decl instanceof Import imp) {
            return "**import** `" + imp.path + "`";

        } else {
            String raw = decl.toString();
            if (raw.length() > 300)
                raw = raw.substring(0, 300) + "\n// ...";
            code.append(raw);
        }

        return "```aurora\n" + code + "\n```";
    }

    /**
     * Validates a document by parsing and type-checking it via {@link AuroraAnalyzer},
     * then publishes diagnostics to the client.
     */
    private void validateDocument(String uri, String text) {
        AuroraLanguageServer.LOGGER.info("validateDocument: %s  textLen=%d", uri, text.length());

        AnalysisResult result = new AuroraAnalyzer().analyze(text, uri);

        if (result.program() != null) {
            astMap.put(uri, result.program());
        }

        AuroraLanguageServer.LOGGER.info("  analysis done: %d diagnostics", result.diagnostics().size());

        List<Diagnostic> lspDiags = result.diagnostics().stream()
                .map(AuroraTextDocumentService::toLspDiagnostic)
                .collect(Collectors.toList());

        try {
            server.getClient().publishDiagnostics(new PublishDiagnosticsParams(uri, lspDiags));
        } catch (Exception e) {
            AuroraLanguageServer.LOGGER.error("Failed to publish diagnostics for " + uri, e);
        }
    }

    /** {@link AuroraDiagnostic} を LSP4J の {@link Diagnostic} に変換する。 */
    private static Diagnostic toLspDiagnostic(AuroraDiagnostic d) {
        Diagnostic diag = new Diagnostic();
        diag.setSource(d.source());
        diag.setMessage(d.message());
        diag.setSeverity(switch (d.severity()) {
            case WARNING     -> DiagnosticSeverity.Warning;
            case INFORMATION -> DiagnosticSeverity.Information;
            case HINT        -> DiagnosticSeverity.Hint;
            default          -> DiagnosticSeverity.Error;
        });
        if (d.location() != null) {
            // SourceLocation は 1-based line、LSP は 0-based
            int startLine = Math.max(0, d.location().line() - 1);
            int startCol  = Math.max(0, d.location().column());
            int endLine   = Math.max(0, d.location().endLine() - 1);
            int endCol    = Math.max(startCol + 1, d.location().endColumn());
            diag.setRange(new Range(new Position(startLine, startCol), new Position(endLine, endCol)));
        } else {
            diag.setRange(new Range(new Position(0, 0), new Position(0, 1)));
        }
        return diag;
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        String uri = params.getTextDocument().getUri();
        Program program = astMap.get(uri);
        AuroraLanguageServer.LOGGER.info("semanticTokensFull request for " + uri + " (AST exists: " + (program != null) + ")");
        if (program == null) {
            return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
        }

        try {
            List<Integer> tokens = SemanticTokenVisitor.getTokens(program, moduleResolver);
            return CompletableFuture.completedFuture(new SemanticTokens(tokens));
        } catch (Exception e) {
            AuroraLanguageServer.LOGGER.error("Failed to generate semantic tokens for " + uri, e);
            return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
        }
    }
}