package com.ulasay.mdpreview

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.intellij.lang.Language
import com.intellij.lang.LanguageUtil
import com.intellij.lexer.EmptyLexer
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import java.awt.Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.toComposeColorOrUnspecified
import org.jetbrains.jewel.foundation.code.MimeType
import org.jetbrains.jewel.foundation.code.highlighting.CodeHighlighter

/**
 * Highlights fenced code blocks with the IDE syntax highlighter of the fence language.
 *
 * Lexer highlighting alone leaves two things plain that the editor colors in a later,
 * semantic pass: annotation names, and JSON property keys. This highlighter adds those
 * two pieces on top of the lexer colors. An identifier that directly follows an `@`
 * takes the scheme's annotation color. In a JSON dialect, a quoted string that the next
 * token follows with a `:` takes the scheme's property key color. Both rules read bare
 * tokens only, so text inside strings and comments never changes color.
 */
class MdCodeHighlighter(private val project: Project) : CodeHighlighter {
  override fun highlight(code: String, mimeType: MimeType?): Flow<AnnotatedString> = flowOf(AnnotatedString(code))

  override fun highlight(code: String, language: String): Flow<AnnotatedString> {
    if (language.isBlank()) return flowOf(AnnotatedString(code))
    val resolved = resolveLanguage(language) ?: return flowOf(AnnotatedString(code))
    val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(resolved, project, null)
      ?.takeIf { it.highlightingLexer !is EmptyLexer }
      ?: return flowOf(AnnotatedString(code))

    val jsonLike = resolved.id.contains("json", ignoreCase = true)
    return flow {
      emit(withContext(Dispatchers.Default) { annotate(code, highlighter, jsonLike) })
    }
  }

  private fun resolveLanguage(name: String): Language? {
    val trimmed = name.trim()
    val canonical = LANGUAGE_ALIASES[trimmed.lowercase()] ?: trimmed
    return LanguageUtil.findRegisteredLanguage(canonical)
  }

  private class Token(val text: String, val keys: Array<TextAttributesKey>)

  private fun annotate(code: String, highlighter: SyntaxHighlighter, jsonLike: Boolean): AnnotatedString {
    val scheme = EditorColorsManager.getInstance().globalScheme
    val annotationAttributes = annotationAttributes(scheme)
    val propertyKeyAttributes = if (jsonLike) propertyKeyAttributes(scheme) else null

    val tokens = ArrayList<Token>()
    with(highlighter.highlightingLexer) {
      start(code)
      var tokenType = tokenType
      while (tokenType != null) {
        tokens.add(Token(tokenText, highlighter.getTokenHighlights(tokenType)))
        advance()
        tokenType = this.tokenType
      }
    }

    fun nextSignificantText(index: Int): String? {
      for (i in index + 1 until tokens.size) {
        if (!tokens[i].text.isBlank()) return tokens[i].text
      }
      return null
    }

    return buildAnnotatedString {
      var lastSignificantToken = ""
      for ((index, token) in tokens.withIndex()) {
        val text = token.text
        val attributes = token.keys.lastOrNull()?.let { scheme.getAttributes(it) ?: it.defaultAttributes }
        val isAnnotationName = token.keys.isEmpty() &&
                               lastSignificantToken == "@" &&
                               IDENTIFIER_REGEX.matches(text)
        val isPropertyKey = propertyKeyAttributes != null &&
                            QUOTE_PREFIX_REGEX.containsMatchIn(text) &&
                            nextSignificantText(index) == ":"
        when {
          isAnnotationName && annotationAttributes != null -> withStyle(annotationAttributes.toSpanStyle()) { append(text) }
          isPropertyKey -> withStyle(propertyKeyAttributes.toSpanStyle()) { append(text) }
          else -> withTextAttributes(attributes) { append(text) }
        }
        if (!text.isBlank()) lastSignificantToken = text
      }
    }
  }

  private fun annotationAttributes(scheme: EditorColorsScheme): TextAttributes? {
    val key = TextAttributesKey.find("ANNOTATION_NAME_ATTRIBUTES")
    return scheme.getAttributes(key) ?: key.defaultAttributes ?: scheme.getAttributes(metadataKey)
  }

  private fun propertyKeyAttributes(scheme: EditorColorsScheme): TextAttributes? {
    val key = TextAttributesKey.find("JSON.PROPERTY_KEY")
    return scheme.getAttributes(key) ?: key.defaultAttributes ?: scheme.getAttributes(stringKey)
  }

  private fun AnnotatedString.Builder.withTextAttributes(textAttributes: TextAttributes?, block: AnnotatedString.Builder.() -> Unit) {
    if (textAttributes == null) {
      return block()
    }
    withStyle(textAttributes.toSpanStyle(), block)
  }

  private fun TextAttributes.toSpanStyle() = SpanStyle(
    color = foregroundColor.toComposeColorOrUnspecified(),
    fontWeight = if (fontType and Font.BOLD != 0) FontWeight.Bold else null,
    fontStyle = if (fontType and Font.ITALIC != 0) FontStyle.Italic else null,
    background = backgroundColor.toComposeColorOrUnspecified(),
    textDecoration = when (effectType) {
      EffectType.LINE_UNDERSCORE -> TextDecoration.Underline
      EffectType.STRIKEOUT -> TextDecoration.LineThrough
      else -> null
    },
  )

  companion object {
    private val metadataKey = TextAttributesKey.find("METADATA")
    private val stringKey = TextAttributesKey.find("JSON.STRING")

    private val IDENTIFIER_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val QUOTE_PREFIX_REGEX = Regex("^[\"']")

    /** Fence info strings that differ from the language ID they name. */
    private val LANGUAGE_ALIASES = mapOf(
      "js" to "JavaScript",
      "ts" to "TypeScript",
      "kt" to "kotlin",
      "kts" to "kotlin",
      "py" to "python",
      "sh" to "shell script",
      "bash" to "shell script",
      "zsh" to "shell script",
      "yml" to "yaml",
      "md" to "Markdown",
    )
  }
}
