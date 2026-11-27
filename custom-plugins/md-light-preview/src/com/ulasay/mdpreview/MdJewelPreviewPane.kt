package com.ulasay.mdpreview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import com.intellij.codeInsight.hint.HintManagerImpl
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ex.Range
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.HintHint
import com.intellij.ui.JBColor
import com.intellij.ui.LightweightHint
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Desktop
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.net.URI
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import org.jetbrains.jewel.markdown.MarkdownMode
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
import org.jetbrains.jewel.markdown.rendering.ImageSourceResolver
import org.jetbrains.jewel.markdown.rendering.InlineMarkdownRenderer
import org.jetbrains.jewel.markdown.rendering.InlinesStyling
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.markdown.rendering.create
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.component.scrollbarContentSafePadding

/**
 * A Swing pane that renders markdown with the Jewel Compose renderer.
 *
 * The pane takes keyboard focus on click, and Ctrl+F with the focus inside it opens the find bar
 * of the preview instead of the platform Find action.
 *
 * [dispose] closes the change popup, releases the line-status tracker of the document, and
 * disposes the loaded blame. The subscriptions of the blame controller use the pane as their
 * parent disposable, so the owner releases the pane with `Disposer.dispose` and not with a
 * direct call.
 */
class MdJewelPreviewPane(
  private val project: Project,
  private val file: VirtualFile,
  document: Document,
  rootDir: Path?,
) : JPanel(BorderLayout()), Disposable {
  private val rawMarkdown = mutableStateOf("")
  internal val find = MdFindState()

  /** Whether the preview shows the source line-number gutter. The choice persists across restarts. */
  internal val showLineNumbers =
    mutableStateOf(PropertiesComponent.getInstance().getBoolean(LINE_NUMBERS_PROPERTY, true))

  internal val blame = MdBlameState()
  internal val lineStatus = MdLineStatusState()
  internal val blameController =
    MdBlameController(project, file, document, blame, lineStatus, this)

  private lateinit var composePanel: JComponent
  private var statusHint: LightweightHint? = null
  private var statusPopupParent: Disposable? = null
  private var popupClickCatcher: AWTEventListener? = null

  init {
    enableNewSwingCompositing()
    val panel = JewelComposePanel {
      MdJewelPreviewContent(
        project, rootDir, rawMarkdown, find, showLineNumbers, blame, lineStatus, blameController,
        onStatusClick = { range, offset -> showStatusPopup(range, offset) },
        onCloseStatusPopup = { closeStatusPopup() },
      )
    }
    composePanel = panel
    add(panel, BorderLayout.CENTER)
    registerFindShortcut()
    // Any edit invalidates the shown change popup.
    document.addDocumentListener(object : DocumentListener {
      override fun documentChanged(event: DocumentEvent) = closeStatusPopup()
    }, this)
  }

  /**
   * Ctrl+F inside this pane opens the find bar of the preview. The binding fires only while the focus
   * is inside the pane, so the text half of the split keeps the platform Find action.
   */
  private fun registerFindShortcut() {
    getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
      KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK),
      FIND_ACTION,
    )
    actionMap.put(FIND_ACTION, object : AbstractAction() {
      override fun actionPerformed(e: ActionEvent) {
        find.open()
      }
    })
  }

  /**
   * Pushes new markdown text into the renderer. Called on the EDT by the editor debounce timer.
   */
  fun update(text: String) {
    rawMarkdown.value = text
  }

  /**
   * Shows the platform change popup next to the clicked status bar of the preview.
   *
   * The popup is the panel the editor gutter shows: the old fragment, word-diff highlighted, with
   * the jump, rollback, diff, and copy actions. The bar's Compose position is converted into the
   * layered-pane coordinates that the hint manager expects.
   */
  fun showStatusPopup(range: Range, offsetInComposeRoot: Offset) {
    val editor = findTextEditor() ?: return
    val tracker = lineStatus.tracker ?: return
    closeStatusPopup()
    val popupParent = Disposer.newDisposable("MdPreviewStatusPopup")
    statusPopupParent = popupParent
    try {
      val renderer = MdStatusPopupRenderer(tracker)
      val panel = renderer.buildPopupPanel(editor, range, popupParent)
      val hint = LightweightHint(panel)
      val layeredPane = SwingUtilities.getRootPane(this)?.layeredPane ?: return
      val point = SwingUtilities.convertPoint(
        composePanel,
        Point(offsetInComposeRoot.x.toInt(), offsetInComposeRoot.y.toInt()),
        layeredPane,
      )
      point.x += 12
      HintManagerImpl.doShowInGivenLocation(hint, editor, point, HintHint(editor, point), true)
      if (hint.isVisible) {
        statusHint = hint
        installPopupClosers(hint, editor, popupParent)
      }
      else {
        closeStatusPopup()
      }
    }
    catch (t: Throwable) {
      closeStatusPopup()
      throw t
    }
  }

  /**
   * Closes the popup the way the platform service does: on any mouse press outside the popup
   * itself, and when either half of the split scrolls away from the anchored block.
   */
  private fun installPopupClosers(hint: LightweightHint, editor: Editor, popupParent: Disposable) {
    val catcher = AWTEventListener { event ->
      if (event.id == MouseEvent.MOUSE_PRESSED) {
        val source = event.source
        val insidePopup = source is Component && SwingUtilities.isDescendingFrom(source, hint.component)
        if (!insidePopup) closeStatusPopup()
      }
    }
    Toolkit.getDefaultToolkit().addAWTEventListener(catcher, AWTEvent.MOUSE_EVENT_MASK)
    popupClickCatcher = catcher
    editor.scrollingModel.addVisibleAreaListener({ closeStatusPopup() }, popupParent)
  }

  fun closeStatusPopup() {
    popupClickCatcher?.let { Toolkit.getDefaultToolkit().removeAWTEventListener(it) }
    popupClickCatcher = null
    statusHint?.hide()
    statusHint = null
    statusPopupParent?.let(Disposer::dispose)
    statusPopupParent = null
  }

  private fun findTextEditor(): Editor? {
    return FileEditorManager.getInstance(project)
      .getEditors(file)
      .filterIsInstance<TextEditor>()
      .firstOrNull()
      ?.editor
  }

  override fun dispose() {
    closeStatusPopup()
    blameController.dispose()
    blame.data?.annotation?.dispose()
  }

  private companion object {
    const val FIND_ACTION = "ulasay-md-find"
  }
}

/** The widest the rendered text column grows to; wider panes add side padding instead. */
private val READING_COLUMN_WIDTH = 1200.dp

/** The body font size, in points above the editor font size: the preview is a reading surface. */
private const val PREVIEW_BODY_FONT_DELTA = 3

/** The properties key that persists the line-number gutter choice of the preview. */
private const val LINE_NUMBERS_PROPERTY = "ulasay.md.preview.lineNumbers"

@Composable
private fun MdJewelPreviewContent(
  project: Project,
  rootDir: Path?,
  rawMarkdown: State<String>,
  find: MdFindState,
  showLineNumbers: MutableState<Boolean>,
  blame: MdBlameState,
  lineStatus: MdLineStatusState,
  blameController: MdBlameController,
  onStatusClick: (Range, Offset) -> Unit,
  onCloseStatusPopup: () -> Unit,
) {
  val styling = remember(JewelTheme.instanceUuid) { createPreviewStyling() }
  val processor = remember {
    MarkdownProcessor(
      listOf(
        AutolinkProcessorExtension,
        GitHubAlertProcessorExtension,
        GitHubStrikethroughProcessorExtension(),
        GitHubTableProcessorExtension,
      ),
      MarkdownMode.EditorPreview(MdLineSynchronizer()),
    )
  }

  // The EditorPreview processor keeps parse state between calls, so parses must not overlap.
  val parseMutex = remember { Mutex() }
  val blocks by produceState<List<MarkdownBlock>>(emptyList(), rawMarkdown.value) {
    value = withContext(Dispatchers.Default) {
      parseMutex.withLock { processor.processMarkdownDocument(rawMarkdown.value) }
    }
  }
  // One blame entry per top-level block; the renderer draws one author band per commit chunk.
  val blameRegions = remember { mutableStateOf<Map<MarkdownBlock, MdBlameRegion?>>(emptyMap()) }
  LaunchedEffect(blame.data, blocks) {
    blameRegions.value = buildBlameRegions(blocks, blame.data)
  }
  LaunchedEffect(blocks, find.query) {
    find.updateMatches(findMatchedBlocks(blocks, find.query))
  }

  val searchResultBackground = EditorColorsManager.getInstance().globalScheme
    .getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES)?.backgroundColor
  LaunchedEffect(searchResultBackground) {
    find.highlight = searchResultBackground?.let { SpanStyle(background = it.toComposeColor()) }
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
    MdLineNumberBlockRenderer(
      styling,
      extensions,
      MdFindInlineRenderer(extensions, find),
      showLineNumbers,
      blame,
      lineStatus,
      blameRegions,
    ) { range, offset -> onStatusClick(range, offset) }
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
    // Scrolling the preview moves the anchored block away, so the change popup closes with it.
    LaunchedEffect(listState.isScrollInProgress) {
      if (listState.isScrollInProgress) onCloseStatusPopup()
    }
    LaunchedEffect(find.current) {
      val blockIndex = find.matches.getOrNull(find.current) ?: return@LaunchedEffect
      // A match lands in the upper middle of the pane, not pinned to its very top.
      val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
      listState.animateScrollToItem(blockIndex, -viewport / 3)
    }
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
            // The top padding leaves room for the corner controls, so they never cover the first block.
            top = 44.dp,
            end = extraSide + 20.dp + scrollbarContentSafePadding(),
            bottom = 56.dp,
          ),
          selectable = true,
          onUrlClick = { url -> Desktop.getDesktop().browse(URI(url)) },
          blockRenderer = blockRenderer,
        )
      }
      Row(
        Modifier
          .align(Alignment.TopEnd)
          .padding(top = 8.dp, end = extraSide + 20.dp + scrollbarContentSafePadding()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        if (find.active) MdFindBar(find)
        if (blame.available) {
          val label =
            if (blame.loading) MdPreviewBundle.message("pill.blame.loading") else MdPreviewBundle.message("pill.blame")
          MdGutterPill(label, blame.active) { on -> blameController.toggle(on) }
        }
        MdGutterPill(MdPreviewBundle.message("pill.line_numbers"), showLineNumbers.value) { enabled ->
          showLineNumbers.value = enabled
          PropertiesComponent.getInstance().setValue(LINE_NUMBERS_PROPERTY, enabled)
        }
      }
    }
  }
}

/**
 * Builds the preview styling on top of the IDE LaF bridge defaults: a roomier body line height,
 * IDE-scaled heading sizes, code blocks as bordered rounded regions with their language label,
 * and a block quote whose colors work in both light and dark themes.
 */
private fun createPreviewStyling(): MarkdownStyling {
  // Body text keeps the IDE UI font family and tracks the editor font size from
  // Settings | Editor | Font, a few points above it: the preview is a reading surface, and
  // proportional UI text at the code font size reads small.
  val editorFontSize = EditorColorsManager.getInstance().globalScheme.editorFontSize
  val base = retrieveDefaultTextStyle(fontSize = (editorFontSize + PREVIEW_BODY_FONT_DELTA).sp)
    .copy(color = Color.Unspecified)
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
    heading = createHeadingStyling(bodyText),
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

/**
 * Heading sizes tuned for the IDE panel: the bridge default doubles the body size for H1,
 * which reads web-like next to editor-scale text. These multipliers keep the heading ladder
 * close to the body; SemiBold weight carries the hierarchy.
 */
private fun createHeadingStyling(base: TextStyle): MarkdownStyling.Heading {
  fun headingInlines(scale: Float, color: Color = base.color): InlinesStyling = InlinesStyling.create(
    base.copy(
      fontSize = base.fontSize * scale,
      lineHeight = base.fontSize * scale * 1.3f,
      fontWeight = FontWeight.SemiBold,
      color = color,
    )
  )

  return MarkdownStyling.Heading(
    h1 = MarkdownStyling.Heading.H1.create(inlinesStyling = headingInlines(1.7f)),
    h2 = MarkdownStyling.Heading.H2.create(inlinesStyling = headingInlines(1.45f)),
    h3 = MarkdownStyling.Heading.H3.create(inlinesStyling = headingInlines(1.25f)),
    h4 = MarkdownStyling.Heading.H4.create(inlinesStyling = headingInlines(1.0f)),
    h5 = MarkdownStyling.Heading.H5.create(inlinesStyling = headingInlines(1.0f)),
    h6 = MarkdownStyling.Heading.H6.create(
      inlinesStyling = headingInlines(0.95f, retrieveColorOrUnspecified("Component.infoForeground"))
    ),
  )
}
