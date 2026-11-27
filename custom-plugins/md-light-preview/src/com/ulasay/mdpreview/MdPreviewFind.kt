package com.ulasay.mdpreview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.ui.JBColor
import org.jetbrains.jewel.bridge.retrieveColorOrUnspecified
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.InlineMarkdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.extensions.MarkdownRendererExtension
import org.jetbrains.jewel.markdown.rendering.DefaultInlineMarkdownRenderer
import org.jetbrains.jewel.markdown.scrolling.ScrollingSynchronizer
import org.jetbrains.jewel.markdown.rendering.InlinesStyling
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.SearchTextField
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.rememberSearchTextFieldState
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The state of the find bar of the preview.
 *
 * The pane owns one instance: the Swing shortcut flips [active], the Compose side reads [query],
 * [matches], and [current], and the inline renderer reads [highlight] while it builds the text.
 */
internal class MdFindState {
  var active by mutableStateOf(false)
    private set

  /** The current query. An empty query disables highlighting. */
  var query by mutableStateOf("")

  /** The lazy-list index of every match, in document order. */
  var matches by mutableStateOf<List<Int>>(emptyList())
    private set

  /** The index into [matches] of the current match, or -1 when there is none. */
  var current by mutableIntStateOf(-1)
    private set

  /** The highlight span the inline renderer applies, or null while highlighting is off. */
  var highlight by mutableStateOf<SpanStyle?>(null)

  fun open() {
    active = true
  }

  fun close() {
    active = false
    query = ""
  }

  fun updateMatches(blockIndices: List<Int>) {
    matches = blockIndices
    current = if (blockIndices.isEmpty()) -1 else 0
  }

  fun next() = move(1)

  fun previous() = move(-1)

  private fun move(delta: Int) {
    if (matches.isEmpty()) return
    current = ((current + delta) % matches.size + matches.size) % matches.size
  }
}

/** Returns the lazy-list index of every block that contains a case-insensitive match of [query]. */
internal fun findMatchedBlocks(blocks: List<MarkdownBlock>, query: String): List<Int> {
  if (query.isEmpty()) return emptyList()
  val result = ArrayList<Int>()
  for ((index, block) in blocks.withIndex()) {
    val text = buildString { appendBlockText(block) }
    var from = 0
    while (true) {
      val at = text.indexOf(query, from, ignoreCase = true)
      if (at < 0) break
      result.add(index)
      from = at + query.length
    }
  }
  return result
}

private fun StringBuilder.appendBlockText(block: MarkdownBlock) {
  when (block) {
    // Top-level blocks arrive wrapped with their source line range when the line-number gutter is on.
    is ScrollingSynchronizer.LocatableMarkdownBlock -> appendBlockText(block.originalBlock)
    is MarkdownBlock.Paragraph -> appendInlines(block.inlineContent)
    is MarkdownBlock.Heading -> appendInlines(block.inlineContent)
    is MarkdownBlock.CodeBlock.FencedCodeBlock -> append(block.content)
    is MarkdownBlock.CodeBlock.IndentedCodeBlock -> append(block.content)
    is MarkdownBlock.BlockQuote -> block.children.forEach { appendLine(); appendBlockText(it) }
    is MarkdownBlock.ListItem -> block.children.forEach { appendLine(); appendBlockText(it) }
    is MarkdownBlock.ListBlock -> block.children.forEach { appendLine(); appendBlockText(it) }
    else -> {}
  }
}

private fun StringBuilder.appendInlines(inlines: List<InlineMarkdown>) {
  for (inline in inlines) {
    when (inline) {
      is InlineMarkdown.Text -> append(inline.content)
      is InlineMarkdown.Code -> append(inline.content)
      is InlineMarkdown.HtmlInline -> append(inline.content)
      is InlineMarkdown.Emphasis -> appendInlines(inline.inlineContent)
      is InlineMarkdown.StrongEmphasis -> appendInlines(inline.inlineContent)
      is InlineMarkdown.Link -> appendInlines(inline.inlineContent)
      is InlineMarkdown.Image -> append(' ').append(inline.alt)
      is InlineMarkdown.CustomDelimitedNode -> appendInlines(inline.inlineContent)
      is InlineMarkdown.SoftLineBreak -> append(' ')
      is InlineMarkdown.HardLineBreak -> appendLine()
    }
  }
}

/**
 * The inline renderer of the preview with find highlighting.
 *
 * It appends plain text in query-colored segments. Text inside emphasis, strong emphasis, and links
 * flows through [appendHighlighted] too, because the default renderers recurse into children after
 * they push their own style.
 */
internal class MdFindInlineRenderer(
  extensions: List<MarkdownRendererExtension>,
  private val find: MdFindState,
) : DefaultInlineMarkdownRenderer(extensions) {
  override fun AnnotatedString.Builder.renderText(
    node: InlineMarkdown.Text,
    styling: InlinesStyling,
    enabled: Boolean,
    currentTextStyle: TextStyle,
  ) {
    appendHighlighted(node.content)
  }

  private fun AnnotatedString.Builder.appendHighlighted(text: String) {
    val query = find.query
    val highlight = find.highlight
    if (query.isEmpty() || highlight == null) {
      append(text)
      return
    }
    var from = 0
    while (from < text.length) {
      val at = text.indexOf(query, from, ignoreCase = true)
      if (at < 0) {
        append(text.substring(from))
        break
      }
      if (at > from) append(text.substring(from, at))
      pushStyle(highlight)
      append(text.substring(at, at + query.length))
      pop()
      from = at + query.length
    }
  }
}

/**
 * The find bar of the preview: a search field with a match counter and jump buttons.
 *
 * Enter jumps to the next match, Shift+Enter to the previous one, and Escape closes the bar.
 */
@Composable
internal fun MdFindBar(find: MdFindState, modifier: Modifier = Modifier) {
  val fieldState = rememberSearchTextFieldState()
  val focusRequester = remember { FocusRequester() }

  LaunchedEffect(fieldState) {
    snapshotFlow { fieldState.searchText }.collect { find.query = it }
  }
  LaunchedEffect(Unit) {
    focusRequester.requestFocus()
  }

  val infoForeground = retrieveColorOrUnspecified("Component.infoForeground")
  val errorColor = JBColor(java.awt.Color(0xE5534B), java.awt.Color(0xDB5C5C)).toComposeColor()
  val hasQuery = find.query.isNotEmpty()
  val count =
    when {
      !hasQuery -> ""
      find.matches.isEmpty() -> MdPreviewBundle.message("find.no_matches")
      else -> MdPreviewBundle.message("find.counter", find.current + 1, find.matches.size)
    }
  val countColor = if (hasQuery && find.matches.isEmpty()) errorColor else infoForeground

  Row(
    modifier =
      modifier
        .background(retrieveColorOrUnspecified("Panel.background"), RoundedCornerShape(10.dp))
        .border(1.dp, retrieveColorOrUnspecified("Component.borderColor"), RoundedCornerShape(10.dp))
        .padding(horizontal = 6.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    SearchTextField(
      state = fieldState,
      modifier = Modifier.width(190.dp).focusRequester(focusRequester),
      placeholder = { Text(MdPreviewBundle.message("find.placeholder")) },
      fallbackKeyEventHandler = { event -> handleFindKey(event, find) },
    )
    Text(
      count,
      modifier = Modifier.widthIn(min = 52.dp).padding(horizontal = 4.dp),
      style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp, color = countColor),
    )
    IconButton(onClick = { find.previous() }) {
      Icon(AllIconsKeys.General.ArrowUp, MdPreviewBundle.message("find.previous"))
    }
    IconButton(onClick = { find.next() }) {
      Icon(AllIconsKeys.General.ArrowDown, MdPreviewBundle.message("find.next"))
    }
    IconButton(onClick = { find.close() }) {
      Icon(AllIconsKeys.General.Close, MdPreviewBundle.message("find.close"))
    }
  }
}

private fun handleFindKey(event: KeyEvent, find: MdFindState): Boolean {
  if (event.type != KeyEventType.KeyDown) return false
  return when (event.key) {
    Key.Enter -> {
      if (event.isShiftPressed) find.previous() else find.next()
      true
    }
    Key.Escape -> {
      find.close()
      true
    }
    else -> false
  }
}
