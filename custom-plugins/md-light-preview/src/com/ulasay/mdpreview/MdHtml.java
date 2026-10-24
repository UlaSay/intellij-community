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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared markdown rendering: parser to HTML, and a Swing HTML pane setup.
 */
final class MdHtml {
  private static final com.intellij.openapi.diagnostic.Logger LOG =
    com.intellij.openapi.diagnostic.Logger.getInstance(MdHtml.class);
  // GitHub markdown palette: a paper-like page and a gray card for code.
  private static final JBColor PAGE_BACKGROUND = new JBColor(0xFFFFFF, 0x0D1117);
  private static final JBColor CODE_BACKGROUND = new JBColor(0xF6F8FA, 0x161B22);
  private static final JBColor INLINE_CODE_BACKGROUND = new JBColor(0xE9EDF1, 0x39414D);
  private static final JBColor BORDER_COLOR = new JBColor(0xD0D7DE, 0x30363D);
  private static final JBColor LINK_COLOR = new JBColor(0x0969DA, 0x58A6FF);

  private MdHtml() {
  }

  private static final Pattern FENCED_CODE = Pattern.compile(
    "<pre><code(?: class=\"language-([\\w+-]+)\")?>(.*?)</code></pre>", Pattern.DOTALL);

  static String render(String markdown) {
    try {
      var flavour = new GFMFlavourDescriptor();
      ASTNode tree = new MarkdownParser(flavour).buildMarkdownTreeFromString(markdown);
      String html = new HtmlGenerator(markdown, tree, flavour, false)
        .generateHtml(new HtmlGenerator.DefaultTagRenderer((node, tag, attributes) -> attributes, false));
      return colorCodeBlocks(html);
    }
    catch (Exception e) {
      LOG.warn("markdown render failed, falling back to plain text", e);
      return "<pre>" + escapeHtml(markdown) + "</pre>";
    }
  }

  private static String colorCodeBlocks(String html) {
    Matcher matcher = FENCED_CODE.matcher(html);
    var result = new StringBuilder(html.length() + 512);
    int copied = 0;
    while (matcher.find()) {
      result.append(html, copied, matcher.start());
      copied = matcher.end();
      String language = matcher.group(1);
      String code = matcher.group(2);
      String colored = MdSyntaxHighlighter.highlight(code, language);
      result.append("<pre><code");
      if (language != null) {
        result.append(" class=\"language-").append(language).append("\"");
      }
      result.append(">").append(colored).append("</code></pre>");
    }
    result.append(html, copied, html.length());
    return result.toString();
  }

  static void install(JEditorPane pane) {
    pane.setContentType("text/html");
    pane.setEditable(false);
    pane.setBorder(JBUI.Borders.empty(16, 48));
    pane.setBackground(PAGE_BACKGROUND);
    var kit = (javax.swing.text.html.HTMLEditorKit)pane.getEditorKit();
    var styleSheet = kit.getStyleSheet();
    Font font = JBFont.label();
    String mono = "JetBrains Mono, Consolas, Monospaced";
    styleSheet.addRule("body {font-family: " + font.getFamily() + "; font-size: " + (font.getSize() + 2)
                       + "pt; color: " + toHex(UIManager.getColor("Label.foreground"))
                       + "; background: " + toHex(PAGE_BACKGROUND) + ";}");
    styleSheet.addRule("h1 {font-size: 175%; font-weight: bold; margin-top: 18; margin-bottom: 10;"
                       + " border-bottom: 1px solid " + toHex(BORDER_COLOR) + "; padding-bottom: 4;}");
    styleSheet.addRule("h2 {font-size: 150%; font-weight: bold; margin-top: 18; margin-bottom: 8;"
                       + " border-bottom: 1px solid " + toHex(BORDER_COLOR) + "; padding-bottom: 3;}");
    styleSheet.addRule("h3 {font-size: 125%; font-weight: bold; margin-top: 14; margin-bottom: 6;}");
    styleSheet.addRule("h4 {font-size: 112%; font-weight: bold; margin-top: 12; margin-bottom: 6;}");
    styleSheet.addRule("p {margin-top: 10; margin-bottom: 10;}");
    styleSheet.addRule("code {font-family: " + mono + "; background: "
                       + toHex(INLINE_CODE_BACKGROUND) + "; padding: 1 3 1 3;}");
    styleSheet.addRule("pre {font-family: " + mono + "; background: " + toHex(CODE_BACKGROUND)
                       + "; margin: 12 0 12 0; padding: 16;}");
    styleSheet.addRule("blockquote {color: " + toHex(JBColor.GRAY) + "; margin: 10 0 10 10;"
                       + " border-left: 3px solid " + toHex(BORDER_COLOR) + "; padding-left: 14;}");
    styleSheet.addRule("a {color: " + toHex(LINK_COLOR) + ";}");
    styleSheet.addRule("table {margin: 12 0 12 0;}");
    styleSheet.addRule("th {background: " + toHex(CODE_BACKGROUND) + "; border-bottom: 2px solid "
                       + toHex(BORDER_COLOR) + "; padding: 8 14 8 6; font-weight: bold;}");
    styleSheet.addRule("td {border-bottom: 1px solid " + toHex(BORDER_COLOR) + "; padding: 8 14 8 6;}");
    styleSheet.addRule("li {margin-top: 5; margin-bottom: 5;}");
    styleSheet.addRule("ul {margin-top: 6; margin-bottom: 6; margin-left: 20; padding-left: 10;}");
    styleSheet.addRule("ol {margin-top: 6; margin-bottom: 6; margin-left: 24; padding-left: 8;}");
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
