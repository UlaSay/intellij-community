package com.ulasay.mdpreview;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
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
    pane.setBorder(JBUI.Borders.empty(10, 14));
    pane.setBackground(UIManager.getColor("Panel.background"));
    var kit = (javax.swing.text.html.HTMLEditorKit)pane.getEditorKit();
    var styleSheet = kit.getStyleSheet();
    Font font = JBFont.label();
    String mono = "JetBrains Mono, Consolas, Monospaced";
    String background = toHex(UIManager.getColor("Panel.background"));
    styleSheet.addRule("body {font-family: " + font.getFamily() + "; font-size: " + (font.getSize() + 1)
                       + "pt; color: " + toHex(UIManager.getColor("Label.foreground"))
                       + "; background: " + background + ";}");
    styleSheet.addRule("h1 {font-size: 175%; font-weight: bold; margin-top: 14; margin-bottom: 8;"
                       + " border-bottom: 1px solid " + toHex(BORDER_COLOR) + "; padding-bottom: 4;}");
    styleSheet.addRule("h2 {font-size: 150%; font-weight: bold; margin-top: 14; margin-bottom: 6;"
                       + " border-bottom: 1px solid " + toHex(BORDER_COLOR) + "; padding-bottom: 3;}");
    styleSheet.addRule("h3 {font-size: 125%; font-weight: bold; margin-top: 12; margin-bottom: 4;}");
    styleSheet.addRule("h4 {font-size: 110%; font-weight: bold; margin-top: 10; margin-bottom: 4;}");
    styleSheet.addRule("p {margin-top: 7; margin-bottom: 7;}");
    styleSheet.addRule("code {font-family: " + mono + "; font-size: 95%; background: "
                       + toHex(CODE_BACKGROUND) + "; padding: 1 3 1 3;}");
    styleSheet.addRule("pre {font-family: " + mono + "; font-size: 95%; background: " + toHex(CODE_BACKGROUND)
                       + "; border: 1px solid " + toHex(BORDER_COLOR) + "; margin: 10 0 10 0; padding: 10;}");
    styleSheet.addRule("blockquote {color: " + toHex(JBColor.GRAY) + "; margin: 8 0 8 8;"
                       + " border-left: 3px solid " + toHex(BORDER_COLOR) + "; padding-left: 12;}");
    styleSheet.addRule("a {color: " + toHex(LINK_COLOR) + ";}");
    styleSheet.addRule("table {border: 1px solid " + toHex(BORDER_COLOR) + "; border-collapse: collapse; margin: 10 0 10 0;}");
    styleSheet.addRule("th {border: 1px solid " + toHex(BORDER_COLOR) + "; background: " + toHex(CODE_BACKGROUND)
                       + "; padding: 6 10 6 10; font-weight: bold;}");
    styleSheet.addRule("td {border: 1px solid " + toHex(BORDER_COLOR) + "; padding: 6 10 6 10;}");
    styleSheet.addRule("li {margin-top: 3; margin-bottom: 3;}");
    styleSheet.addRule("ul {margin-top: 4; margin-bottom: 4; margin-left: 20; padding-left: 10;}");
    styleSheet.addRule("ol {margin-top: 4; margin-bottom: 4; margin-left: 24; padding-left: 8;}");
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
