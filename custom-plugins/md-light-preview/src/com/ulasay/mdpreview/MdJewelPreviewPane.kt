package com.ulasay.mdpreview

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import javax.swing.JPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.bridge.retrieveColorOrUnspecified
import org.jetbrains.jewel.bridge.retrieveEditorColorScheme
import org.jetbrains.jewel.bridge.theme.retrieveDefaultTextStyle
import org.jetbrains.jewel.bridge.theme.retrieveEditorTextStyle
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.foundation.enableNewSwingCompositing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create
import org.jetbrains.jewel.intui.markdown.bridge.styling.extensions.github.alerts.create
import org.jetbrains.jewel.intui.markdown.bridge.styling.extensions.github.tables.create
import org.jetbrains.jewel.markdown.LazyMarkdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.extensions.autolink.AutolinkProcessorExtension
import org.jetbrains.jewel.markdown.extensions.github.alerts.AlertStyling
import org.jetbrains.jewel.markdown.extensions.github.alerts.GitHubAlertProcessorExtension
import org.jetbrains.jewel.markdown.extensions.github.alerts.GitHubAlertRendererExtension
import org.jetbrains.jewel.markdown.extensions.github.strikethrough.GitHubStrikethroughProcessorExtension
import org.jetbrains.jewel.markdown.extensions.github.strikethrough.GitHubStrikethroughRendererExtension
import org.jetbrains.jewel.markdown.extensions.github.tables.GfmTableStyling
import org.jetbrains.jewel.markdown.extensions.github.tables.GitHubTableProcessorExtension
import org.jetbrains.jewel.markdown.extensions.github.tables.GitHubTableRendererExtension
import org.jetbrains.jewel.markdown.extensions.images.Coil3ImageRendererExtension
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.markdown.rendering.DefaultMarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.ImageSourceResolver
import org.jetbrains.jewel.markdown.rendering.InlineMarkdownRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.markdown.rendering.create
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.component.scrollbarContentSafePadding

/**
 * A Swing pane that renders markdown with the Jewel Compose renderer.
 *
 * It exists only when the Compose UI plugin modules are on the classpath. The editor probes
 * [org.jetbrains.jewel.bridge.JewelComposePanel] before it creates this pane, and falls back
 * to the Swing HTML engine otherwise.
 */
class MdJewelPreviewPane(project: Project, rootDir: Path?) : JPanel(BorderLayout()) {
  private val rawMarkdown = mutableStateOf("")

  init {
    enableNewSwingCompositing()
    add(
      JewelComposePanel {
        MdJewelPreviewContent(project, rootDir, rawMarkdown)
      },
      BorderLayout.CENTER,
    )
  }

  /**
   * Pushes new markdown text into the renderer. Called on the EDT by the editor debounce timer.
   */
  fun update(text: String) {
    rawMarkdown.value = text
  }
}

/** The widest the rendered text column grows to; wider panes add side padding instead. */
private val READING_COLUMN_WIDTH = 1200.dp

@Composable
private fun MdJewelPreviewContent(project: Project, rootDir: Path?, rawMarkdown: State<String>) {
  val styling = remember(JewelTheme.instanceUuid) { createPreviewStyling() }
  val processor = remember {
    MarkdownProcessor(
      listOf(
        AutolinkProcessorExtension,
        GitHubAlertProcessorExtension,
        GitHubStrikethroughProcessorExtension(),
        GitHubTableProcessorExtension,
      ),
    )
  }

  val blocks by produceState<List<MarkdownBlock>>(emptyList(), rawMarkdown.value) {
    value = withContext(Dispatchers.Default) { processor.processMarkdownDocument(rawMarkdown.value) }
  }

  val coilContext = LocalPlatformContext.current
  val imageExtension = remember(coilContext) { Coil3ImageRendererExtension.withDefaultLoader(coilContext) }
  val blockRenderer = remember(styling, imageExtension) {
    val extensions = listOf(
      imageExtension,
      GitHubAlertRendererExtension(AlertStyling.create(), styling),
      GitHubStrikethroughRendererExtension,
      GitHubTableRendererExtension(GfmTableStyling.create(), styling),
    )
    DefaultMarkdownBlockRenderer(styling, extensions, InlineMarkdownRenderer.create(extensions))
  }
  val imageResolver = remember(rootDir) {
    if (rootDir == null) ImageSourceResolver.create() else ImageSourceResolver.create(rootDir, false)
  }

  val codeHighlighter = remember { MdCodeHighlighter(project) }
  ProvideMarkdownStyling(
    imageSourceResolver = imageResolver,
    markdownStyling = styling,
    markdownProcessor = processor,
    markdownBlockRenderer = blockRenderer,
    codeHighlighter = codeHighlighter,
  ) {
    val listState = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
      // Side padding keeps the text column readable on very wide panes while the scroll
      // container, and its scrollbar, still span the full pane.
      val extraSide = ((maxWidth - READING_COLUMN_WIDTH) / 2).coerceAtLeast(0.dp)
      VerticallyScrollableContainer(scrollState = listState) {
        LazyMarkdown(
          blocks = blocks,
          state = listState,
          contentPadding = PaddingValues(
            start = 32.dp + extraSide,
            top = 24.dp,
            end = extraSide + 20.dp + scrollbarContentSafePadding(),
            bottom = 56.dp,
          ),
          selectable = true,
          onUrlClick = { url -> Desktop.getDesktop().browse(URI(url)) },
          blockRenderer = blockRenderer,
        )
      }
    }
  }
}

/**
 * Builds the preview styling on top of the IDE LaF bridge defaults: a roomier body line height,
 * code blocks as bordered rounded regions with their language label, and a block quote whose
 * colors work in both light and dark themes.
 */
private fun createPreviewStyling(): MarkdownStyling {
  // Body text keeps the IDE UI font family and tracks the editor font size from
  // Settings | Editor | Font, one step above it: the preview is a reading surface, and
  // proportional UI text at the code font size reads small.
  val editorFontSize = EditorColorsManager.getInstance().globalScheme.editorFontSize
  val base = retrieveDefaultTextStyle(fontSize = (editorFontSize + 1).sp).copy(color = Color.Unspecified)
  val bodyText = base.copy(lineHeight = base.fontSize * 1.6f)

  val editorScheme = retrieveEditorColorScheme()
  val codeTextStyle = retrieveEditorTextStyle().copy(color = editorScheme.defaultForeground.toComposeColor())
  val codeBackground = editorScheme.defaultBackground.toComposeColor()
  val codeBorderColor = retrieveColorOrUnspecified("Group.separatorColor")
  val codeShape = RoundedCornerShape(6.dp)

  val fencedCode = MarkdownStyling.Code.Fenced.create(
    textStyle = codeTextStyle,
    padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    shape = codeShape,
    background = codeBackground,
    borderWidth = 1.dp,
    borderColor = codeBorderColor,
    fillWidth = true,
    scrollsHorizontally = true,
    infoTextStyle = TextStyle(color = retrieveColorOrUnspecified("Component.infoForeground"), fontSize = 11.sp),
    infoPadding = PaddingValues(bottom = 6.dp),
    infoPosition = MarkdownStyling.Code.Fenced.InfoPosition.TopStart,
  )
  val indentedCode = MarkdownStyling.Code.Indented.create(
    textStyle = codeTextStyle,
    padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    shape = codeShape,
    background = codeBackground,
    borderWidth = 1.dp,
    borderColor = codeBorderColor,
    fillWidth = true,
    scrollsHorizontally = true,
  )

  return MarkdownStyling.create(
    baseTextStyle = bodyText,
    blockQuote = MarkdownStyling.BlockQuote.create(
      padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
      lineWidth = 4.dp,
      lineColor = JBColor(java.awt.Color(0xD0D7DE), java.awt.Color(0x444C56)).toComposeColor(),
      textColor = JBColor(java.awt.Color(0x57606A), java.awt.Color(0x9198A1)).toComposeColor(),
    ),
    code = MarkdownStyling.Code.create(
      editorTextStyle = codeTextStyle,
      indented = indentedCode,
      fenced = fencedCode,
    ),
  )
}
