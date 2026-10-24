package com.ulasay.mdpreview;

import com.intellij.ui.JBColor;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex syntax coloring for fenced code blocks, on the HTML-escaped text the
 * markdown generator emits. GitHub colors, light and dark pairs.
 */
final class MdSyntaxHighlighter {
  private static final JBColor KEYWORD = new JBColor(0xCF222E, 0xFF7B72);
  private static final JBColor STRING = new JBColor(0x0A3069, 0xA5D6FF);
  private static final JBColor COMMENT = new JBColor(0x6E7781, 0x8B949E);
  private static final JBColor NUMBER = new JBColor(0x0550AE, 0x79C0FF);
  private static final JBColor ANNOTATION = new JBColor(0x8250DF, 0xD2A8FF);
  private static final JBColor TAG = new JBColor(0x116329, 0x7EE787);
  private static final JBColor TYPE = new JBColor(0x953800, 0xFFA657);
  private static final JBColor METHOD = new JBColor(0x6639BA, 0xD2A8FF);
  private static final JBColor CONSTANT = new JBColor(0x0550AE, 0x79C0FF);

  private static final Set<String> CURLY_KEYWORDS = Set.of(
    "abstract", "as", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
    "continue", "data", "default", "do", "double", "else", "enum", "extends", "false", "final", "finally",
    "float", "for", "fun", "get", "goto", "if", "implements", "import", "in", "init", "instanceof", "int",
    "interface", "internal", "is", "long", "native", "new", "null", "object", "open", "operator", "out",
    "override", "package", "private", "protected", "public", "record", "return", "sealed", "set", "short",
    "static", "strictfp", "super", "suspend", "switch", "synchronized", "this", "throw", "throws", "trait",
    "transient", "true", "try", "typealias", "val", "var", "void", "volatile", "when", "while", "with", "yield");

  private static final Set<String> PY_KEYWORDS = Set.of(
    "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else",
    "except", "False", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "None",
    "nonlocal", "not", "or", "pass", "raise", "return", "True", "try", "while", "with", "yield");

  private static final Set<String> SQL_KEYWORDS = Set.of(
    "add", "alter", "and", "as", "asc", "by", "create", "delete", "desc", "distinct", "drop", "else",
    "end", "from", "group", "having", "in", "index", "inner", "insert", "into", "is", "join", "key",
    "left", "limit", "not", "null", "offset", "on", "or", "order", "outer", "primary", "references",
    "right", "select", "set", "table", "then", "union", "update", "values", "when", "where");

  // The generator escapes quotes as &quot;, so string tokens match the entity form.
  private static final String Q = "&quot;";

  private static final Pattern CURLY = Pattern.compile(
    "(?<comment>/\\*[\\s\\S]*?\\*/|//[^\\n]*)"
    + "|(?<string>" + Q + "(?:(?!" + Q + ")[^\\n])*" + Q + "|'(?:[^'\\\\\\n]|\\\\.)*'|`[^\\n]*?`)"
    + "|(?<annotation>@[A-Za-z_]\\w*)"
    + "|(?<number>\\b\\d[\\d_]*(?:\\.\\d+)?[fFlLdD]?\\b|\\b0[xX][0-9A-Fa-f_]+\\b)"
    + "|(?<method>[A-Za-z_]\\w*(?=\\())"
    + "|(?<word>[A-Za-z_]\\w*)");

  private static final Pattern PY = Pattern.compile(
    "(?<comment>#[^\\n]*)"
    + "|(?<string>" + Q + "(?:(?!" + Q + ")[^\\n])*" + Q + "|'(?:[^'\\\\\\n]|\\\\.)*')"
    + "|(?<number>\\b\\d[\\d_]*(?:\\.\\d+)?\\b)"
    + "|(?<method>[A-Za-z_]\\w*(?=\\())"
    + "|(?<word>[A-Za-z_]\\w*)");

  private static final Pattern HASH = Pattern.compile(
    "(?<comment>#[^\\n]*)"
    + "|(?<string>" + Q + "(?:(?!" + Q + ")[^\\n])*" + Q + "|'(?:[^'\\\\\\n]|\\\\.)*')"
    + "|(?<number>\\b\\d[\\d_]*(?:\\.\\d+)?\\b)");

  private static final Pattern XML = Pattern.compile(
    "(?<comment><!--[\\s\\S]*?-->)"
    + "|(?<tag></?[A-Za-z_][\\w:.-]*)"
    + "|(?<string>" + Q + "(?:(?!" + Q + ")[^\\n])*" + Q + ")");

  private static final Pattern SQL = Pattern.compile(
    "(?<comment>--[^\\n]*|/\\*[\\s\\S]*?\\*/)"
    + "|(?<string>'(?:[^'\\\\\\n]|\\\\.)*')"
    + "|(?<number>\\b\\d[\\d_]*(?:\\.\\d+)?\\b)"
    + "|(?<word>[A-Za-z_]\\w*)");

  private MdSyntaxHighlighter() {
  }

  static String highlight(String escapedCode, String language) {
    if (language == null) {
      return escapedCode;
    }
    String lang = language.toLowerCase(Locale.ROOT);
    Pattern pattern;
    Set<String> keywords;
    switch (lang) {
      case "java", "kotlin", "kt", "kts", "js", "javascript", "ts", "typescript", "json", "c", "cpp", "csharp",
           "go", "rust", "scala", "swift", "dart", "groovy", "gradle" -> {
        pattern = CURLY;
        keywords = CURLY_KEYWORDS;
      }
      case "python", "py" -> {
        pattern = PY;
        keywords = PY_KEYWORDS;
      }
      case "sh", "bash", "shell", "zsh", "yaml", "yml", "properties", "ini", "toml", "dockerfile", "makefile",
           "conf", "cfg" -> {
        pattern = HASH;
        keywords = null;
      }
      case "xml", "html", "svg", "xsd" -> {
        pattern = XML;
        keywords = null;
      }
      case "sql" -> {
        pattern = SQL;
        keywords = SQL_KEYWORDS;
      }
      default -> {
        return escapedCode;
      }
    }
    return tokenize(escapedCode, pattern, keywords, lang);
  }

  private static String tokenize(String code, Pattern pattern, Set<String> keywords, String lang) {
    Matcher matcher = pattern.matcher(code);
    var result = new StringBuilder(code.length() + 256);
    int copied = 0;
    boolean sql = "sql".equals(lang);
    while (matcher.find()) {
      result.append(code, copied, matcher.start());
      copied = matcher.end();
      String token = matcher.group();
      String color = colorOf(matcher, token, keywords, sql);
      if (color == null) {
        result.append(token);
      }
      else {
        result.append("<span style=\"color: ").append(color).append("\">").append(token).append("</span>");
      }
    }
    result.append(code, copied, code.length());
    return result.toString();
  }

  private static String colorOf(Matcher matcher, String token, Set<String> keywords, boolean sql) {
    if (group(matcher, "comment") != null) {
      return hex(COMMENT);
    }
    if (group(matcher, "string") != null) {
      return hex(STRING);
    }
    if (group(matcher, "number") != null) {
      return hex(NUMBER);
    }
    if (group(matcher, "annotation") != null) {
      return hex(ANNOTATION);
    }
    if (group(matcher, "tag") != null) {
      return hex(TAG);
    }
    if (group(matcher, "method") != null) {
      return hex(METHOD);
    }
    if (group(matcher, "word") != null && keywords != null) {
      String word = sql ? token.toLowerCase(Locale.ROOT) : token;
      if (keywords.contains(word)) {
        return hex(KEYWORD);
      }
      if (!sql && token.length() > 1 && token.equals(token.toUpperCase(Locale.ROOT))) {
        return hex(CONSTANT);
      }
      if (!sql && Character.isUpperCase(token.charAt(0))) {
        return hex(TYPE);
      }
    }
    return null;
  }

  // matcher.group(name) throws for a group the pattern does not define, it does not return null.
  private static String group(Matcher matcher, String name) {
    try {
      return matcher.group(name);
    }
    catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String hex(JBColor color) {
    return String.format("#%06X", 0xFFFFFF & color.getRGB());
  }
}
