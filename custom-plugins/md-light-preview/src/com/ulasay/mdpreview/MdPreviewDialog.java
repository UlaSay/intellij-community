package com.ulasay.mdpreview;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.Timer;
import javax.swing.event.HyperlinkEvent;

/**
 * A non-modal preview window for one markdown document.
 * Used where the file editor does not apply, for example the standalone LightEdit mode.
 */
final class MdPreviewDialog extends DialogWrapper {
  private static MdPreviewDialog activeDialog;

  private final VirtualFile file;
  private final Document document;
  private final DocumentListener documentListener = new DocumentListener() {
    @Override
    public void documentChanged(@NotNull DocumentEvent event) {
      debounce.restart();
    }
  };
  private final JEditorPane htmlPane = new JEditorPane();
  private final Timer debounce;
  private volatile boolean disposed;

  private MdPreviewDialog(@Nullable Project project, @NotNull VirtualFile file, @NotNull Editor editor) {
    super(project, false, false);
    this.file = file;
    this.document = editor.getDocument();
    this.debounce = new Timer(250, event -> renderNow());
    setTitle("Light Preview - " + file.getName());
    init();
    debounce.setRepeats(false);
    document.addDocumentListener(documentListener);
    htmlPane.addHyperlinkListener(event -> {
      if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
        BrowserUtil.browse(event.getURL());
      }
    });
    renderNow();
  }

  static void showFor(@NotNull Project project, @NotNull VirtualFile file, @NotNull Editor editor) {
    if (activeDialog != null && !activeDialog.disposed) {
      if (activeDialog.file.equals(file)) {
        activeDialog.renderNow();
        activeDialog.show();
        return;
      }
      activeDialog.close(CLOSE_EXIT_CODE);
    }
    activeDialog = new MdPreviewDialog(project, file, editor);
    activeDialog.show();
  }

  @Override
  protected JComponent createCenterPanel() {
    MdHtml.install(htmlPane);
    JBScrollPane scrollPane = new JBScrollPane(htmlPane);
    scrollPane.setPreferredSize(JBUI.size(520, 640));
    return scrollPane;
  }

  @Override
  public void dispose() {
    if (disposed) {
      return;
    }
    disposed = true;
    debounce.stop();
    document.removeDocumentListener(documentListener);
    super.dispose();
  }

  private void renderNow() {
    if (disposed) {
      return;
    }
    MdHtml.update(htmlPane, document.getText());
  }
}
