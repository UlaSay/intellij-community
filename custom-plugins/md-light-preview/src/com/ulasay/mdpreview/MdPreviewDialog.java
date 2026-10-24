package com.ulasay.mdpreview;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import org.intellij.markdown.ast.ASTNode;
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor;
import org.intellij.markdown.html.HtmlGenerator;
import org.intellij.markdown.parser.MarkdownParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;
import java.awt.Font;

/**
 * A non-modal preview window for one markdown document.
 * Renders through the Swing HTML engine, so no JCEF is involved.
 */
final class MdPreviewDialog extends DialogWrapper {
  private static final JBColor CODE_BACKGROUND = new JBColor(0xF6F8FA, 0x2B2D30);
  private static final JBColor BORDER_COLOR = new JBColor(0xD0D7DE, 0x444C56);
  private static final JBColor LINK_COLOR = new JBColor(0x0969DA, 0x58A6FF);

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
    htmlPane.setEditable(false);
    installStyleSheet();
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
    String markdown = document.getText();
    String body;
    try {
      var flavour = new GFMFlavourDescriptor();
      ASTNode tree = new MarkdownParser(flavour).buildMarkdownTreeFromString(markdown);
      body = new HtmlGenerator(markdown, tree, flavour, false)
        .generateHtml(new HtmlGenerator.DefaultTagRenderer((node, tag, attributes) -> attributes, false));
    }
    catch (Exception e) {
      body = "<pre>" + escapeHtml(markdown) + "</pre>";
    }
    htmlPane.setText("<html><body>" + body + "</body></html>");
    htmlPane.setCaretPosition(0);
  }

  private void installStyleSheet() {
    htmlPane.setContentType("text/html");
    var kit = (javax.swing.text.html.HTMLEditorKit)htmlPane.getEditorKit();
    var styleSheet = kit.getStyleSheet();
    Font font = JBFont.label();
    String color = toHex(UIManager.getColor("Label.foreground"));
    styleSheet.addRule("body {font-family: " + font.getFamily() + "; font-size: " + font.getSize() + "pt; color: " + color + ";}");
    styleSheet.addRule("h1 {font-size: 160%; font-weight: bold; margin-top: 8; margin-bottom: 6;}");
    styleSheet.addRule("h2 {font-size: 140%; font-weight: bold; margin-top: 8; margin-bottom: 6;}");
    styleSheet.addRule("h3 {font-size: 120%; font-weight: bold; margin-top: 6; margin-bottom: 4;}");
    styleSheet.addRule("h4 {font-size: 110%; font-weight: bold; margin-top: 6; margin-bottom: 4;}");
    styleSheet.addRule("p {margin-top: 4; margin-bottom: 4;}");
    styleSheet.addRule("code {font-family: Monospaced; background: " + toHex(CODE_BACKGROUND) + ";}");
    styleSheet.addRule("pre {font-family: Monospaced; background: " + toHex(CODE_BACKGROUND) + "; margin: 4; padding: 6;}");
    styleSheet.addRule("blockquote {color: " + toHex(JBColor.GRAY) + "; margin-left: 12;}");
    styleSheet.addRule("a {color: " + toHex(LINK_COLOR) + ";}");
    styleSheet.addRule("table {border: 1px solid " + toHex(BORDER_COLOR) + "; border-collapse: collapse; margin: 4;}");
    styleSheet.addRule("th {border: 1px solid " + toHex(BORDER_COLOR) + "; padding: 4; font-weight: bold;}");
    styleSheet.addRule("td {border: 1px solid " + toHex(BORDER_COLOR) + "; padding: 4;}");
    styleSheet.addRule("li {margin-top: 2; margin-bottom: 2;}");
  }

  private static String toHex(java.awt.Color color) {
    return String.format("#%06X", 0xFFFFFF & color.getRGB());
  }

  private static String escapeHtml(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
