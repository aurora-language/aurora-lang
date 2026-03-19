package aurora.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * The entry point for the Aurora Language Server.
 * This class implements the {@link LanguageServer} interface to provide LSP capabilities
 * such as hover, definition lookup, completion, and semantic highlighting.
 */
public class AuroraLanguageServer implements LanguageServer, LanguageClientAware {

    /** The client proxy used to send diagnostics and other messages back to the IDE. */
    private LanguageClient client;

    /** The service responsible for text document-related operations (editing, diagnostics, etc.). */
    private final AuroraTextDocumentService textDocumentService;

    /** The service responsible for workspace-wide operations. */
    private final AuroraWorkspaceService workspaceService;

    /**
     * Initializes a new AuroraLanguageServer and its associated services.
     */
    public AuroraLanguageServer() {
        this.textDocumentService = new AuroraTextDocumentService(this);
        this.workspaceService = new AuroraWorkspaceService(this);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);

        capabilities.setDefinitionProvider(true);
        capabilities.setHoverProvider(true);

        CompletionOptions completionOptions = new CompletionOptions();
        completionOptions.setResolveProvider(false);
        capabilities.setCompletionProvider(completionOptions);

        SemanticTokensWithRegistrationOptions semanticTokens = new SemanticTokensWithRegistrationOptions();
        semanticTokens.setLegend(new SemanticTokensLegend(
                Arrays.asList("class", "type", "function", "variable", "parameter", "property"),
                Arrays.asList("declaration", "definition", "readonly", "static")));
        semanticTokens.setFull(true);
        capabilities.setSemanticTokensProvider(semanticTokens);

        return CompletableFuture.completedFuture(new InitializeResult(capabilities));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        System.exit(0);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    @Override
    public void setTrace(SetTraceParams params) {
        // No-op
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
    }

    public LanguageClient getClient() {
        return client;
    }
}