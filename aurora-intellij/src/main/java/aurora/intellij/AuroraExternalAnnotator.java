package aurora.intellij;

import aurora.analyzer.AnalysisResult;
import aurora.analyzer.AuroraAnalyzer;
import aurora.analyzer.AuroraDiagnostic;
import aurora.analyzer.ModuleResolver;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class AuroraExternalAnnotator extends ExternalAnnotator<AuroraExternalAnnotator.CollectedInfo, List<AuroraDiagnostic>> {

    public record CollectedInfo(String text, String filePath, @Nullable Path projectRoot) {}

    @Override
    public @Nullable CollectedInfo collectInformation(@NotNull PsiFile file) {
        VirtualFile vf = file.getVirtualFile();
        String filePath = vf != null ? vf.getPath() : "<editor>";
        Path root = vf != null ? findProjectRoot(vf) : null;
        return new CollectedInfo(file.getText(), filePath, root);
    }

    @Override
    public @Nullable List<AuroraDiagnostic> doAnnotate(CollectedInfo info) {
        ModuleResolver modules = new ModuleResolver();
        if (info.projectRoot() != null) {
            modules.setProjectRoot(info.projectRoot());
        }
        AnalysisResult result = new AuroraAnalyzer()
                .analyze(info.text(), info.filePath(), modules);
        return result.diagnostics();
    }

    @Override
    public void apply(@NotNull PsiFile file,
                      List<AuroraDiagnostic> diagnostics,
                      @NotNull AnnotationHolder holder) {
        if (diagnostics == null || diagnostics.isEmpty()) return;

        Document doc = FileDocumentManager.getInstance()
                .getDocument(file.getVirtualFile());

        for (AuroraDiagnostic d : diagnostics) {
            TextRange range = toTextRange(d, doc, file.getTextLength());
            holder.newAnnotation(toSeverity(d.severity()), d.message())
                    .range(range)
                    .create();
        }
    }

    private static @Nullable Path findProjectRoot(@NotNull VirtualFile vf) {
        VirtualFile dir = vf.getParent();
        while (dir != null) {
            if (dir.findChild("aurora")          != null
                    || dir.findChild("settings.gradle") != null
                    || dir.findChild("build.gradle")    != null
                    || dir.findChild("pom.xml")         != null
                    || dir.findChild("package.json")    != null) {
                return Paths.get(dir.getPath());
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static TextRange toTextRange(AuroraDiagnostic d, Document doc, int fileLength) {
        if (d.location() == null || doc == null) {
            return TextRange.from(0, Math.min(1, fileLength));
        }

        int lineCount = doc.getLineCount();

        // SourceLocation は 1-based、Document は 0-based
        int startLine = Math.min(Math.max(d.location().line() - 1, 0), lineCount - 1);
        int endLine   = Math.min(Math.max(d.location().endLine() - 1, 0), lineCount - 1);

        int startOffset = doc.getLineStartOffset(startLine) + Math.max(d.location().column(), 0);
        int endOffset   = doc.getLineStartOffset(endLine)   + Math.max(d.location().endColumn(), 0);

        startOffset = Math.min(startOffset, fileLength);
        endOffset   = Math.min(Math.max(endOffset, startOffset + 1), fileLength);

        return new TextRange(startOffset, endOffset);
    }

    private static HighlightSeverity toSeverity(AuroraDiagnostic.Severity severity) {
        return switch (severity) {
            case WARNING     -> HighlightSeverity.WARNING;
            case INFORMATION -> HighlightSeverity.INFORMATION;
            case HINT        -> HighlightSeverity.WEAK_WARNING;
            default          -> HighlightSeverity.ERROR;
        };
    }
}