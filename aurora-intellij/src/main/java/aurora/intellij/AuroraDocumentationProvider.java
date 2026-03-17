package aurora.intellij;

import aurora.analyzer.AnalysisResult;
import aurora.analyzer.AuroraAnalyzer;
import aurora.analyzer.NodeFinder;
import aurora.analyzer.SymbolResolver;
import aurora.parser.tree.Node;
import aurora.parser.tree.Program;
import aurora.parser.tree.decls.*;
import com.intellij.lang.documentation.AbstractDocumentationProvider;
import com.intellij.lang.documentation.DocumentationMarkup;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.richcopy.HtmlSyntaxInfoUtil;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.stream.Collectors;

public class AuroraDocumentationProvider extends AbstractDocumentationProvider {

    @Override
    public @Nullable PsiElement getCustomDocumentationElement(
            @NotNull Editor editor,
            @NotNull PsiFile file,
            @Nullable PsiElement contextElement,
            int targetOffset) {
        if (!(file instanceof AuroraParserDefinition.AuroraFile)) return null;
        return contextElement != null ? contextElement : file;
    }

    @Override
    public @Nullable String generateDoc(PsiElement element,
                                        @Nullable PsiElement originalElement) {
        PsiElement target = originalElement != null ? originalElement : element;
        PsiFile file = target.getContainingFile();
        if (!(file instanceof AuroraParserDefinition.AuroraFile)) return null;

        AnalysisResult result = new AuroraAnalyzer().analyze(file.getText(), file.getName());
        if (result.program() == null) return null;

        Document doc = PsiDocumentManager.getInstance(target.getProject()).getDocument(file);
        if (doc == null) return null;

        int offset = target.getTextOffset();
        int line   = doc.getLineNumber(offset) + 1;
        int col    = offset - doc.getLineStartOffset(line - 1);

        NodeFinder finder = new NodeFinder(line, col);
        List<Node> path = finder.findPath(result.program());
        Node node = (path != null && !path.isEmpty()) ? path.getLast() : null;

        Node decl = SymbolResolver.resolve(node, path, result.program(), null);
        if (decl == null) return null;

        return buildDoc(decl, target.getProject());
    }

    // ── HTML 生成 ──────────────────────────────────────────────────────

    private static String buildDoc(Node decl, Project project) {
        String signature = buildSignature(decl);

        StringBuilder html = new StringBuilder();

        // ── 定義セクション（シグネチャをシンタックスハイライト付きで表示） ──
        html.append(DocumentationMarkup.DEFINITION_START);
        appendHighlighted(html, signature, project);
        html.append(DocumentationMarkup.DEFINITION_END);

        // ── コンテンツセクション（doc コメント） ──
        String comment = buildComment(decl);
        if (!comment.isEmpty()) {
            html.append(DocumentationMarkup.CONTENT_START);
            html.append(escape(comment).replace("\n", "<br/>"));
            html.append(DocumentationMarkup.CONTENT_END);
        }

        // ── パラメータセクション ──
        if (decl instanceof FunctionDecl func
                && func.params != null && !func.params.isEmpty()) {
            html.append(DocumentationMarkup.SECTIONS_START);
            html.append(DocumentationMarkup.SECTION_HEADER_START)
                    .append("Parameters")
                    .append(DocumentationMarkup.SECTION_SEPARATOR);
            for (int i = 0; i < func.params.size(); i++) {
                if (i > 0) html.append("<br/>");
                appendHighlighted(html, func.params.get(i).toString(), project);
            }
            html.append(DocumentationMarkup.SECTION_END);
            html.append(DocumentationMarkup.SECTIONS_END);
        }

        return html.toString();
    }

    /**
     * Aurora のシンタックスハイライターを使ってコードを色付けし sb に追記する。
     * HtmlSyntaxInfoUtil が使えない環境へのフォールバックとして
     * プレーンテキストの <code> ブロックも用意する。
     */
    private static void appendHighlighted(StringBuilder sb, String code, Project project) {
        try {
            var scheme = EditorColorsManager.getInstance().getGlobalScheme();
            HtmlSyntaxInfoUtil.appendHighlightedByLexerAndEncodedAsHtmlCodeSnippet(
                    sb,
                    project,
                    AuroraLang.INSTANCE,
                    code,
                    false,
                    1.0f);
        } catch (Exception e) {
            // フォールバック: プレーンエスケープ
            sb.append("<code>").append(escape(code)).append("</code>");
        }
    }

    // ── シグネチャ文字列の構築（プレーンテキスト、ハイライトは appendHighlighted に任せる） ──

    private static String buildSignature(Node decl) {
        StringBuilder sb = new StringBuilder();

        if (decl instanceof FunctionDecl func) {
            if (func.visibility != null) sb.append(func.visibility).append(" ");
            if (func.modifiers != null)
                func.modifiers.forEach(m -> sb.append(m.toString().toLowerCase()).append(" "));
            sb.append("fun ");
            if (func.receiverType != null)
                sb.append(func.receiverType).append(".");
            sb.append(func.name).append("(");
            if (func.params != null) {
                for (int i = 0; i < func.params.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(func.params.get(i));
                }
            }
            sb.append(")");
            if (func.returnType != null)
                sb.append(": ").append(func.returnType);

        } else if (decl instanceof FieldDecl field) {
            if (field.visibility != null) sb.append(field.visibility).append(" ");
            if (field._static) sb.append("static ");
            sb.append(field.declType.name().toLowerCase()).append(" ").append(field.name);
            if (field.type != null && !field.type.name.equals("none"))
                sb.append(": ").append(field.type);
            else if (field.init != null)
                sb.append(" = ").append(field.init);

        } else if (decl instanceof ParamDecl param) {
            sb.append(param.name);
            if (param.type != null) sb.append(": ").append(param.type);

        } else if (decl instanceof ClassDecl cls) {
            if (cls.visibility != null) sb.append(cls.visibility).append(" ");
            if (cls.isAbstract) sb.append("abstract ");
            sb.append("class ").append(cls.name);
            if (cls.typeParams != null && !cls.typeParams.isEmpty())
                sb.append("<")
                        .append(cls.typeParams.stream().map(Object::toString).collect(Collectors.joining(", ")))
                        .append(">");
            if (cls.superClass != null) sb.append(" : ").append(cls.superClass);
            if (cls.interfaces != null && !cls.interfaces.isEmpty())
                sb.append(cls.superClass != null ? ", " : " : ")
                        .append(cls.interfaces.stream().map(Object::toString).collect(Collectors.joining(", ")));

        } else if (decl instanceof RecordDecl rec) {
            if (rec.visibility != null) sb.append(rec.visibility).append(" ");
            sb.append("record ").append(rec.name).append("(");
            if (rec.parameters != null) {
                for (int i = 0; i < rec.parameters.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(rec.parameters.get(i));
                }
            }
            sb.append(")");

        } else if (decl instanceof InterfaceDecl iface) {
            if (iface.visibility != null) sb.append(iface.visibility).append(" ");
            sb.append("trait ").append(iface.name);
            if (iface.typeParams != null && !iface.typeParams.isEmpty())
                sb.append("<")
                        .append(iface.typeParams.stream().map(Object::toString).collect(Collectors.joining(", ")))
                        .append(">");

        } else if (decl instanceof EnumDecl en) {
            if (en.visibility != null) sb.append(en.visibility).append(" ");
            sb.append("enum ").append(en.name);

        } else if (decl instanceof ConstructorDecl ctor) {
            if (ctor.visibility != null) sb.append(ctor.visibility).append(" ");
            sb.append("constructor(");
            if (ctor.params != null) {
                for (int i = 0; i < ctor.params.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(ctor.params.get(i));
                }
            }
            sb.append(")");

        } else if (decl instanceof Program.Import imp) {
            sb.append("use ").append(imp.path);

        } else {
            String raw = decl.toString();
            sb.append(raw.length() > 300 ? raw.substring(0, 300) + "\n// ..." : raw);
        }

        return sb.toString();
    }

    private static String buildComment(Node decl) {
        if (decl.leadingComments == null || decl.leadingComments.isEmpty()) return "";
        return decl.leadingComments.stream()
                .filter(c -> c.startsWith("/**"))
                .map(c -> c.replaceAll("^/\\*\\*\\s*", "")
                        .replaceAll("\\s*\\*/$", "")
                        .replaceAll("(?m)^\\s*\\*\\s?", "")
                        .trim())
                .collect(Collectors.joining("\n"));
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}