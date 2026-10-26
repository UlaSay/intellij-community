package com.ulasay.mdpreview

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import javax.swing.JPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.JewelComposePanel
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

@Composable
private fun MdJewelPreviewContent(project: Project, rootDir: Path?, rawMarkdown: State<String>) {
  val styling = remember(JewelTheme.instanceUuid) { MarkdownStyling.create() }
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

  ProvideMarkdownStyling(
    project = project,
    imageSourceResolver = imageResolver,
    markdownStyling = styling,
    markdownProcessor = processor,
    markdownBlockRenderer = blockRenderer,
  ) {
    val listState = rememberLazyListState()
    VerticallyScrollableContainer(scrollState = listState) {
      LazyMarkdown(
        blocks = blocks,
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp + scrollbarContentSafePadding(), bottom = 16.dp),
        selectable = true,
        onUrlClick = { url -> Desktop.getDesktop().browse(URI(url)) },
        blockRenderer = blockRenderer,
      )
    }
  }
}
