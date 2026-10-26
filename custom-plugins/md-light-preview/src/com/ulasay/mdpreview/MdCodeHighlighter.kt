package com.ulasay.mdpreview

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.lang.Language
import com.intellij.lang.LanguageUtil
import com.intellij.lexer.EmptyLexer
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import java.awt.Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.toComposeColorOrUnspecified
import org.jetbrains.jewel.foundation.code.MimeType
import org.jetbrains.jewel.foundation.code.highlighting.CodeHighlighter

/**
 * Highlights fenced code blocks the way the editor does.
 *
 * The flow emits twice. The first emission carries the lexer colors, which are ready at
 * once. The second carries the full editor colors: a hidden editor runs the platform's
 * own semantic highlighting over the block, and its markup layers that set a foreground
 * color are merged on top of the lexer colors. Markers with an error-stripe color, such
 * as unresolved-reference errors, are skipped, so a snippet without imports never turns
 * red.
 *
 * While the semantic pass settles, or when it cannot run, the lexer colors stay, and two
 * token rules fill the common gaps: an identifier after an `@` takes the scheme's
 * annotation color, and a quoted string followed by `:` in a JSON dialect takes the
 * scheme's property key color.
 */
class MdCodeHighlighter(private val project: Project) : CodeHighlighter {
  override fun highlight(code: String, mimeType: MimeType?): Flow<AnnotatedString> = flowOf(AnnotatedString(code))

  override fun highlight(code: String, language: String): Flow<AnnotatedString> {
    if (language.isBlank()) return flowOf(AnnotatedString(code))
    val resolved = resolveLanguage(language) ?: return flowOf(AnnotatedString(code))
    val fileType = resolved.associatedFileType ?: return flowOf(AnnotatedString(code))
    val syntaxHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(resolved, project, null)
      ?.takeIf { it.highlightingLexer !is EmptyLexer }

    return flow {
      if (syntaxHighlighter == null) {
        emit(AnnotatedString(code))
        return@flow
      }
      emit(withContext(Dispatchers.Default) { lexerColors(code, syntaxHighlighter, resolved) })
      val semantic = withContext(Dispatchers.Default) { editorColorsOrNull(code, fileType, syntaxHighlighter, resolved) }
      if (semantic != null) emit(semantic)
    }
  }

  private fun resolveLanguage(name: String): Language? {
    val trimmed = name.trim()
    val canonical = LANGUAGE_ALIASES[trimmed.lowercase()] ?: trimmed
    return LanguageUtil.findRegisteredLanguage(canonical)
  }

  // --- Stage 1: lexer colors, plus the two token rules -------------------------

  private class Token(val text: String, val keys: Array<TextAttributesKey>)

  private fun lexerColors(code: String, highlighter: SyntaxHighlighter, language: Language): AnnotatedString {
    val scheme = EditorColorsManager.getInstance().globalScheme
    val annotationAttributes = annotationAttributes(scheme)
    val jsonLike = language.id.contains("json", ignoreCase = true)
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

  // --- Stage 2: full editor colors over a hidden editor ------------------------

  /**
   * Runs the platform highlighting in a hidden editor and merges the result over the
   * lexer colors. Returns null when the editor or the analysis is unavailable, so the
   * lexer emission stays in place.
   */
  private suspend fun editorColorsOrNull(
    code: String,
    fileType: FileType,
    syntaxHighlighter: SyntaxHighlighter,
    language: Language,
  ): AnnotatedString? {
    val application = ApplicationManager.getApplication()

    // PSI creation and markup reads need read access; wrap them instead of touching
    // the model bare from a background coroutine.
    val psiFile = runCatching {
      ReadAction.compute<PsiFile?, RuntimeException> {
        PsiFileFactory.getInstance(project).createFileFromText("md_fence.${fileType.defaultExtension}", fileType, code)
      }
    }.getOrNull() ?: return null

    var editor: com.intellij.openapi.editor.Editor? = null
    try {
      fun createEditorOnEdt(): com.intellij.openapi.editor.Editor? {
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        val created = EditorFactory.getInstance().createEditor(document, project)
        DaemonCodeAnalyzer.getInstance(project).restart(created)
        return created
      }

      // The flow may be collected on the EDT, where invokeAndWait is not allowed.
      editor = if (application.isDispatchThread) createEditorOnEdt()
      else {
        var created: com.intellij.openapi.editor.Editor? = null
        application.invokeAndWait { created = createEditorOnEdt() }
        created
      }
      val created = editor ?: return null
      waitForHighlightingToSettle(created)

      // Snapshot the marker ranges under a read action, then build the string off-thread.
      data class Mark(val attributes: TextAttributes, val start: Int, val end: Int)
      val marks = ReadAction.compute<List<Mark>, RuntimeException> {
        created.markupModel.allHighlighters.mapNotNull { highlighter ->
          val attributes = keepableAttributes(highlighter) ?: return@mapNotNull null
          val start = highlighter.startOffset.coerceIn(0, code.length)
          val end = highlighter.endOffset.coerceIn(start, code.length)
          if (end > start) Mark(attributes, start, end) else null
        }
      }
      return buildAnnotatedString {
        append(lexerColors(code, syntaxHighlighter, language))
        for (mark in marks) {
          addStyle(mark.attributes.toSpanStyle(), mark.start, mark.end)
        }
      }
    }
    catch (t: Throwable) {
      return null
    }
    finally {
      val toRelease = editor
      if (toRelease != null) {
        if (application.isDispatchThread) EditorFactory.getInstance().releaseEditor(toRelease)
        else application.invokeLater { runCatching { EditorFactory.getInstance().releaseEditor(toRelease) } }
      }
    }
  }

  /**
   * Takes the attributes of a semantic marker, or null when it must not color the text.
   * Diagnostic markers carry an underline effect, and a snippet without imports must not
   * inherit the editor's error look.
   */
  private fun keepableAttributes(highlighter: RangeHighlighter): TextAttributes? {
    val attributes = highlighter.textAttributes ?: return null
    if (attributes.foregroundColor == null) return null
    return when (attributes.effectType) {
      EffectType.WAVE_UNDERSCORE, EffectType.BOLD_LINE_UNDERSCORE, EffectType.BOLD_DOTTED_LINE -> null
      else -> attributes
    }
  }

  /** Waits until the daemon stops adding markers, with a bounded overall budget. */
  private suspend fun waitForHighlightingToSettle(editor: com.intellij.openapi.editor.Editor) {
    fun signature(): String = ReadAction.compute<String, RuntimeException> { markerSignature(editor) }
    var last = signature()
    var stableSince = 0L
    val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
      delay(STABILIZATION_POLL_MS)
      val current = signature()
      if (current == last) {
        stableSince += STABILIZATION_POLL_MS
        if (stableSince >= STABLE_WINDOW_MS) return
      }
      else {
        last = current
        stableSince = 0
      }
    }
  }

  private fun markerSignature(editor: com.intellij.openapi.editor.Editor): String {
    return editor.markupModel.allHighlighters.joinToString(",") { "${it.startOffset}-${it.endOffset}" }
  }

  // --- Attribute mapping -------------------------------------------------------

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

    private const val SETTLE_TIMEOUT_MS = 3_000L
    private const val STABILIZATION_POLL_MS = 100L
    private const val STABLE_WINDOW_MS = 400L

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
