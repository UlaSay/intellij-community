package com.ulasay.mdpreview;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBFont;
import org.intellij.markdown.ast.ASTNode;
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor;
import org.intellij.markdown.html.HtmlGenerator;
import org.intellij.markdown.parser.MarkdownParser;

import javax.swing.JEditorPane;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/**
 * Shared markdown rendering: parser to HTML, and a Swing HTML pane setup.
 */
final class MdHtml {
  private static final JBColor CODE_BACKGROUND = new JBColor(0xF6F8FA, 0x2B2D30);
  private static final JBColor BORDER_COLOR = new JBColor(0xD0D7DE, 0x444C56);
  private static final JBColor LINK_COLOR = new JBColor(0x0969DA, 0x58A6FF);

  private MdHtml() {
  }

  static String render(String markdown) {
    try {
      var flavour = new GFMFlavourDescriptor();
      ASTNode tree = new MarkdownParser(flavour).buildMarkdownTreeFromString(markdown);
      return new HtmlGenerator(markdown, tree, flavour, false)
        .generateHtml(new HtmlGenerator.DefaultTagRenderer((node, tag, attributes) -> attributes, false));
    }
    catch (Exception e) {
      return "<pre>" + escapeHtml(markdown) + "</pre>";
    }
  }

  static void install(JEditorPane pane) {
    pane.setContentType("text/html");
    pane.setEditable(false);
    var kit = (javax.swing.text.html.HTMLEditorKit)pane.getEditorKit();
    var styleSheet = kit.getStyleSheet();
    Font font = JBFont.label();
    styleSheet.addRule("body {font-family: " + font.getFamily() + "; font-size: " + font.getSize()
                       + "pt; color: " + toHex(UIManager.getColor("Label.foreground")) + ";}");
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

  static void update(JEditorPane pane, String markdown) {
    pane.setText("<html><body>" + render(markdown) + "</body></html>");
    pane.setCaretPosition(0);
  }

  private static String toHex(Color color) {
    return String.format("#%06X", 0xFFFFFF & color.getRGB());
  }

  private static String escapeHtml(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
