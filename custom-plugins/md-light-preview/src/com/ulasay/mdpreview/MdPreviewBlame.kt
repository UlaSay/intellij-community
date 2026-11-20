package com.ulasay.mdpreview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.actions.AnnotationsSettings
import com.intellij.openapi.vcs.annotate.AnnotationProvider
import com.intellij.openapi.vcs.annotate.FileAnnotation
import com.intellij.openapi.vcs.ex.LineStatusMarkerPopupActions
import com.intellij.openapi.vcs.ex.LineStatusMarkerPopupPanel
import com.intellij.openapi.vcs.ex.LineStatusTracker
import com.intellij.openapi.vcs.ex.LineStatusTrackerI
import com.intellij.openapi.vcs.ex.LineStatusTrackerListener
import com.intellij.openapi.vcs.ex.LineStatusTrackerMarkerRenderer
import com.intellij.openapi.vcs.ex.Range
import com.intellij.openapi.vcs.ex.RollbackLineStatusAction
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vcs.impl.LineStatusTrackerManager
import com.intellij.openapi.vcs.impl.UpToDateLineNumberProviderImpl
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import org.jetbrains.jewel.bridge.retrieveColorOrUnspecified
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.scrolling.ScrollingSynchronizer
import org.jetbrains.jewel.ui.component.Text
import java.awt.Color as AwtColor
import java.awt.Point

/**
 * The blame state of the preview: whether the gutter shows annotations, and the latest loaded data.
 *
 * [available] turns true when the file is under a VCS that can annotate. It gates the blame pill.
 */
internal class MdBlameState {
  var active by mutableStateOf(false)

  var loading by mutableStateOf(false)

  var available by mutableStateOf(false)

  var data by mutableStateOf<MdBlameData?>(null)
    private set

  fun accept(data: MdBlameData?) {
    this.data = data
    loading = false
  }
}

/**
 * The per-line status stripes of the preview: the tracker of the open document plus a version that
 * grows whenever the tracker reports new ranges.
 */
internal class MdLineStatusState {
  var tracker by mutableStateOf<LineStatusTracker<*>?>(null)
  var version by mutableIntStateOf(0)
    private set

  fun bump() {
    version++
  }
}

/** The blame of one block: the newest change that touches it, or local-only changes. */
internal data class MdBlockBlame(val author: String?, val time: Long, val rank: Int)

/** The blame of one block plus whether the block starts a new commit chunk. */
internal data class MdBlameRegion(val blame: MdBlockBlame, val startsChunk: Boolean)

/**
 * The blame of every top-level block, in one pass.
 *
 * Text lands only on chunk starts, so consecutive blocks of one commit read as a single colored
 * region, the way the editor annotate gutter paints one band per commit chunk.
 */
internal fun buildBlameRegions(
  blocks: List<MarkdownBlock>,
  data: MdBlameData?,
): Map<MarkdownBlock, MdBlameRegion?> {
  if (data == null) return emptyMap()
  var previousRank = Int.MIN_VALUE
  return buildMap {
    for (block in blocks) {
      val lines = (block as? ScrollingSynchronizer.LocatableMarkdownBlock)?.lines
      val info = lines?.let(data::infoForBlock)
      if (info == null) {
        put(block, null)
        continue
      }
      val starts = info.rank != previousRank
      previousRank = info.rank
      put(block, MdBlameRegion(info, starts))
    }
  }
}

/**
 * A loaded annotation prepared for per-block lookup.
 *
 * The arrays hold author, time, and revision rank per line of the annotated content, newest
 * revision first. [infoForBlock] walks the block's current lines, maps each to an annotated line,
 * and keeps the newest result. A line with no annotated counterpart counts as a local change.
 */
internal class MdBlameData(
  val annotation: FileAnnotation,
  private val lineMapper: UpToDateLineNumberProviderImpl,
) {
  private val lineCount = annotation.getLineCount().coerceAtLeast(0)
  private val authors = arrayOfNulls<String>(lineCount)
  private val times = LongArray(lineCount) { -1 }
  private val ranks = IntArray(lineCount) { -1 }
  private val revisionCount: Int
  private val orderedColors: List<AwtColor>
  private val authorColors: Map<String, AwtColor>

  init {
    val revisions = annotation.revisions ?: emptyList()
    val byNumber = HashMap<VcsRevisionNumber, VcsFileRevision>()
    for (revision in revisions) byNumber[revision.revisionNumber] = revision
    val ordered = revisions.sortedByDescending { it.revisionDate?.time ?: 0L }
    val rankOf = HashMap<VcsRevisionNumber, Int>()
    ordered.forEachIndexed { index, revision -> rankOf[revision.revisionNumber] = index }
    revisionCount = ordered.size
    val scheme = EditorColorsManager.getInstance().globalScheme
    orderedColors = AnnotationsSettings.getInstance().getOrderedColors(scheme)
    authorColors = buildAuthorColors(revisions, scheme)
    for (line in 0 until lineCount) {
      val number = annotation.getLineRevisionNumber(line) ?: continue
      authors[line] = byNumber[number]?.author?.substringBefore(" <")
      times[line] = annotation.getLineDate(line)?.time ?: byNumber[number]?.revisionDate?.time ?: -1
      ranks[line] = rankOf[number] ?: -1
    }
  }

  /**
   * Returns the newest change inside [lines], or a local-only entry when every mapped line is a
   * local edit. Returns null when the block has no blame information at all.
   */
  fun infoForBlock(lines: IntRange): MdBlockBlame? {
    var bestTime = -1L
    var bestLine = -1
    var localChange = false
    for (line in lines) {
      val annotated = lineMapper.getLineNumber(line)
      if (annotated < 0) {
        localChange = true
        continue
      }
      if (annotated >= lineCount) continue
      if (times[annotated] >= bestTime) {
        bestTime = times[annotated]
        bestLine = annotated
      }
    }
    if (bestLine < 0) return if (localChange) MdBlockBlame(null, -1, -1) else null
    return MdBlockBlame(authors[bestLine], times[bestLine], ranks[bestLine])
  }

  /** The annotate heatmap color of a revision rank, mirroring the editor gutter palette. */
  fun colorForRank(rank: Int): Color {
    if (rank < 0 || revisionCount == 0 || orderedColors.isEmpty()) return Color.Unspecified
    val index = orderedColors.size * rank / revisionCount
    return orderedColors[index.coerceIn(0, orderedColors.lastIndex)].toComposeColor()
  }

  /**
   * The background color of an author, the way the editor annotate gutter assigns one. Files with
   * a single author get no colors at all, matching the editor, where the map is dropped then.
   */
  fun backgroundForAuthor(author: String?): Color? {
    val color = authorColors[author ?: return null] ?: return null
    return color.toComposeColor()
  }

  private fun buildAuthorColors(
    revisions: List<VcsFileRevision>,
    scheme: com.intellij.openapi.editor.colors.EditorColorsScheme,
  ): Map<String, AwtColor> {
    val palette = AnnotationsSettings.getInstance().getAuthorsColors(scheme)
    val authors = revisions.mapNotNull { it.author?.substringBefore(" <") }.toSortedSet()
    if (authors.size < 2 || palette.isEmpty()) return emptyMap()
    val colors = HashMap<String, AwtColor>(authors.size)
    authors.forEachIndexed { index, author -> colors[author] = palette[index % palette.size] }
    return colors
  }
}

/**
 * Loads blame for the open file and keeps the line-status tracker of its document fresh.
 *
 * Annotation runs on a pooled thread, because the git backend spends it in a git blame process.
 * The result lands on the EDT. A save of the file triggers a reload while the blame gutter is on.
 */
internal class MdBlameController(
  private val project: Project,
  private val file: VirtualFile,
  private val document: Document,
  internal val blame: MdBlameState,
  private val lineStatus: MdLineStatusState,
  parent: Disposable,
) {
  private var annotationProvider: AnnotationProvider? = null

  init {
    val vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(file)
    annotationProvider = vcs?.annotationProvider
    blame.available = annotationProvider != null

    val trackerManager = LineStatusTrackerManager.getInstanceImpl(project)
    var tracker = trackerManager.getLineStatusTracker(document)
    if (tracker == null) trackerManager.requestTrackerFor(document, this)
    attachTracker(tracker)

    with(project.messageBus.connect(parent)) {
      subscribe(LineStatusTrackerManager.TOPIC, object : LineStatusTrackerManager.Listener {
        override fun onTrackerAdded(tracker: LineStatusTracker<*>) = acceptTracker(tracker)

        override fun onTrackerRemoved(tracker: LineStatusTracker<*>) {
          if (tracker.document === document) lineStatus.tracker = null
        }

        override fun onTrackerBecomeValid(tracker: LineStatusTracker<*>) = acceptTracker(tracker)
      })
      subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
        override fun after(events: List<VFileEvent>) {
          if (blame.active && events.any { it is VFileContentChangeEvent && it.file == file }) annotate()
        }
      })
    }
  }

  fun toggle(on: Boolean) {
    blame.active = on
    if (on) annotate()
  }

  /** Reloads the annotation. The previous annotation is disposed once the new one is in place. */
  fun annotate() {
    val provider = annotationProvider ?: return
    if (blame.loading) return
    blame.loading = true
    ApplicationManager.getApplication().executeOnPooledThread {
      val result = runCatching { provider.annotate(file) }
      ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        val oldData = blame.data
        if (result.isSuccess) {
          blame.accept(MdBlameData(result.getOrThrow(), UpToDateLineNumberProviderImpl(document, project)))
          oldData?.annotation?.dispose()
        }
        else {
          blame.accept(oldData)
        }
      }
    }
  }

  private fun acceptTracker(tracker: LineStatusTracker<*>) {
    if (tracker.document === document) attachTracker(tracker)
  }

  private fun attachTracker(tracker: LineStatusTracker<*>?) {
    if (tracker == null) return
    lineStatus.tracker = tracker
    tracker.addListener(object : LineStatusTrackerListener {
      override fun onBecomingValid() = lineStatus.bump()

      override fun onRangesChanged() = lineStatus.bump()
    })
  }
}

/**
 * The uncommitted changes of a block, or empty when the block matches the repository version.
 *
 * The order mirrors the editor gutter stack: added lines first, then modified, then
 * whitespace-changed, and the small deleted-boundary markers last.
 */
internal fun rangesForBlock(lines: IntRange, state: MdLineStatusState): List<Range> {
  val tracker = state.tracker ?: return emptyList()
  var inserted: Range? = null
  var modified: Range? = null
  var whitespace: Range? = null
  val deleted = ArrayList<Range>()
  for (line in lines) {
    val range = tracker.getRangeForLine(line) ?: continue
    when (range.type) {
      Range.INSERTED -> if (inserted == null) inserted = range
      Range.MODIFIED -> if (modified == null) modified = range
      Range.EQUAL -> if (whitespace == null) whitespace = range
      Range.DELETED -> if (deleted.isEmpty() || deleted.last() !== range) deleted.add(range)
    }
  }
  return buildList {
    inserted?.let(::add)
    modified?.let(::add)
    whitespace?.let(::add)
    addAll(deleted)
  }
}

/** "3 天前"-style relative time of a timestamp. */
internal fun relativeTime(time: Long): String {
  val minutes = (System.currentTimeMillis() - time) / 60_000
  return when {
    minutes < 1 -> "刚刚"
    minutes < 60 -> "$minutes 分钟前"
    minutes < 24 * 60 -> "${minutes / 60} 小时前"
    minutes < 30 * 24 * 60 -> "${minutes / (24 * 60)} 天前"
    minutes < 365 * 24 * 60 -> "${minutes / (30 * 24 * 60)} 个月前"
    else -> "${minutes / (365 * 24 * 60)} 年前"
  }
}

/**
 * The popup renderer of the preview: the platform change popup, fed by the tracker.
 *
 * The toolbar mirrors the editor gutter popup: jump to the previous and the next change, roll back
 * the range, show the full diff, and copy the range.
 */
internal class MdStatusPopupRenderer(private val tracker: LineStatusTrackerI<*>) : LineStatusTrackerMarkerRenderer(tracker) {
  /**
   * Builds the platform change popup. [createPopupPanel] is protected, so this wrapper hands the
   * panel to the preview pane, which shows it next to the clicked status bar.
   */
  fun buildPopupPanel(
    editor: Editor,
    range: Range,
    popupDisposable: Disposable,
  ): LineStatusMarkerPopupPanel = createPopupPanel(editor, range, null, popupDisposable)

  override fun createToolbarActions(editor: Editor, range: Range, mousePosition: Point?): List<AnAction> = listOf(
    LineStatusMarkerPopupActions.ShowPrevChangeMarkerAction(editor, tracker, range, this),
    LineStatusMarkerPopupActions.ShowNextChangeMarkerAction(editor, tracker, range, this),
    RollbackRangeAction(editor, range),
    LineStatusMarkerPopupActions.ShowLineStatusRangeDiffAction(editor, tracker, range),
    LineStatusMarkerPopupActions.CopyLineStatusRangeAction(editor, tracker, range),
  )

  override fun createEditorContextMenuActions(editor: Editor, range: Range, mousePosition: Point?): List<AnAction> = listOf(
    LineStatusMarkerPopupActions.CopyLineStatusRangeAction(editor, tracker, range),
    Separator.getInstance(),
    LineStatusMarkerPopupActions.ToggleByWordDiffAction(editor, tracker, range, mousePosition, this).apply {
      templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Never
    },
  )

  private inner class RollbackRangeAction(editor: Editor, range: Range) :
    LineStatusMarkerPopupActions.RangeMarkerAction(editor, tracker, range, IdeActions.SELECTED_CHANGES_ROLLBACK) {
    override fun isEnabled(editor: Editor, range: Range): Boolean = true

    override fun actionPerformed(editor: Editor, range: Range) {
      RollbackLineStatusAction.rollback(tracker, range, editor)
    }
  }
}


/**
 * A quiet pill that toggles a gutter feature, the same style as the find bar.
 *
 * The enabled text color marks the on state, the muted info color the off state.
 */
@Composable
internal fun MdGutterPill(label: String, turnedOn: Boolean, onToggle: (Boolean) -> Unit) {
  val style =
    if (turnedOn) JewelTheme.defaultTextStyle.copy(fontSize = 11.sp)
    else JewelTheme.defaultTextStyle.copy(fontSize = 11.sp, color = retrieveColorOrUnspecified("Component.infoForeground"))
  Row(
    modifier =
      Modifier
        .background(retrieveColorOrUnspecified("Panel.background"), RoundedCornerShape(10.dp))
        .border(1.dp, retrieveColorOrUnspecified("Component.borderColor"), RoundedCornerShape(10.dp))
        .clickable { onToggle(!turnedOn) }
        .padding(horizontal = 10.dp, vertical = 5.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(label, style = style)
  }
}
