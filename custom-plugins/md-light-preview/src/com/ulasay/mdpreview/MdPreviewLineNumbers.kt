package com.ulasay.mdpreview

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.vcs.ex.Range
import com.intellij.ui.JBColor
import org.jetbrains.jewel.bridge.retrieveColorOrUnspecified
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.extensions.MarkdownRendererExtension
import org.jetbrains.jewel.markdown.rendering.DefaultMarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.InlineMarkdownRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.markdown.scrolling.ScrollingSynchronizer
import org.jetbrains.jewel.ui.component.Text

/**
 * Labels every top-level markdown block with its source line range.
 *
 * The processor calls [acceptBlockSpans] during parsing. Wrapping the block into a
 * [ScrollingSynchronizer.LocatableMarkdownBlock] carries the range into the rendered block list.
 * The block renderer then reads it for the line-number gutter. All other methods stay empty:
 * this synchronizer never scrolls, it only labels.
 */
internal class MdLineSynchronizer : ScrollingSynchronizer() {
  override fun acceptBlockSpans(block: MarkdownBlock, sourceRange: IntRange): MarkdownBlock =
    ScrollingSynchronizer.LocatableMarkdownBlock(block, sourceRange)

  override fun acceptGlobalPosition(block: MarkdownBlock, coordinates: LayoutCoordinates) {}

  override fun acceptTextLayout(block: MarkdownBlock, textLayout: TextLayoutResult) {}

  override fun beforeProcessing() {}

  override fun afterProcessing() {}

  override suspend fun scrollToCoordinate(y: Int, animationSpec: AnimationSpec<Float>) {}

  override suspend fun findYCoordinateToScroll(sourceLine: Int): Int = 0
}

/**
 * The block renderer of the preview with the source gutter: line numbers, blame, and status.
 *
 * Top-level blocks arrive wrapped in [ScrollingSynchronizer.LocatableMarkdownBlock] from the
 * processor. Each wrapped block renders as a row: the gutter built from the block's line range,
 * then the block through the default renderer. One entry per block keeps the mapping exact.
 * Rendered text wraps, so inner rendered lines have no source line of their own.
 */
internal class MdLineNumberBlockRenderer(
  styling: MarkdownStyling,
  renderingExtensions: List<MarkdownRendererExtension>,
  inlineRenderer: InlineMarkdownRenderer,
  private val showLineNumbers: State<Boolean>,
  private val blame: MdBlameState,
  private val lineStatus: MdLineStatusState,
  private val blameRegions: State<Map<MarkdownBlock, MdBlameRegion?>>,
  private val onStatusClick: (Range, Offset) -> Unit,
) : DefaultMarkdownBlockRenderer(styling, renderingExtensions, inlineRenderer) {
  @Composable
  override fun RenderBlock(block: MarkdownBlock, enabled: Boolean, onUrlClick: (String) -> Unit, modifier: Modifier) {
    val locatable = block as? ScrollingSynchronizer.LocatableMarkdownBlock
    // The default renderer does not know the wrapper, so both paths below hand it the plain block.
    if (locatable == null || (!showLineNumbers.value && !blame.active)) {
      super.RenderBlock(locatable?.originalBlock ?: block, enabled, onUrlClick, modifier)
      return
    }

    // Reading the version ties the stripes to tracker updates that arrive after composition.
    lineStatus.version
    val blockLines = locatable.lines
    val ranges = rangesForBlock(blockLines, lineStatus)
    val barRange = ranges.firstOrNull { it.type != Range.DELETED }
    val deletedRanges = ranges.filter { it.type == Range.DELETED }
    val region = blameRegions.value[block]
    val info = region?.blame
    val commitColor = if (info != null) blame.data?.colorForRank(info.rank) ?: Color.Unspecified else Color.Unspecified
    val stripeColor =
      when {
        barRange != null -> statusColor(barRange.type)
        commitColor != Color.Unspecified -> commitColor
        else -> null
      }
    val deletedColor = if (deletedRanges.isNotEmpty()) statusColor(Range.DELETED) else null
    // One author band spans every block of a commit chunk, like the editor annotate gutter.
    val authorBand = if (info != null) blame.data?.backgroundForAuthor(info.author) else null

    // The stripe is drawn behind the whole item, full height, like the editor gutter marker.
    // Hover widens it to the left at once, the way a gutter repaint does; a press inside the left
    // band opens the change popup.
    var itemOrigin by remember { mutableStateOf(Offset.Zero) }
    var stripeHovered by remember { mutableStateOf(false) }
    val stripeWidth = if (stripeHovered) 7.dp else 4.dp
    val stripeBandPx = with(LocalDensity.current) { 10.dp.toPx() }
    val activeRange = ranges.firstOrNull()

    Row(
      modifier
        .onGloballyPositioned { itemOrigin = it.positionInRoot() }
        .pointerInput(stripeColor, activeRange) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              val position = event.changes.firstOrNull()?.position ?: continue
              val inBand = position.x < stripeBandPx
              when (event.type) {
                PointerEventType.Move -> stripeHovered = stripeColor != null && inBand
                PointerEventType.Exit -> stripeHovered = false
                PointerEventType.Press -> if (activeRange != null && inBand) {
                  onStatusClick(activeRange, itemOrigin + Offset(0f, position.y))
                }
              }
            }
          }
        }
        .drawBehind {
          val base = 4.dp.toPx()
          if (stripeColor != null) {
            val width = stripeWidth.toPx()
            drawRoundRect(
              color = stripeColor,
              topLeft = Offset(base - width, 0f),
              size = Size(width, size.height),
              cornerRadius = CornerRadius(width / 2),
            )
          }
          if (authorBand != null) {
            drawRect(
              color = authorBand,
              topLeft = Offset(8.dp.toPx(), 0f),
              size = Size(BLAME_WIDTH.toPx() + 8.dp.toPx(), size.height),
            )
          }
          if (deletedColor != null) {
            // Deleted lines have no line of their own; the editor paints a small marker at the
            // boundary, placed here at its share of the block's height.
            val lineCount = (blockLines.last - blockLines.first + 1).coerceAtLeast(1)
            val markerHeight = 10.dp.toPx()
            for (range in deletedRanges) {
              val fraction = (range.line1 - blockLines.first).toFloat() / lineCount
              val y = (size.height * fraction).coerceIn(0f, (size.height - markerHeight).coerceAtLeast(0f))
              drawRoundRect(
                color = deletedColor,
                topLeft = Offset(0f, y),
                size = Size(base, markerHeight),
                cornerRadius = CornerRadius(2.dp.toPx()),
              )
            }
          }
        },
    ) {
      // The gutter stays out of text selection: dragging over a passage selects its words only,
      // not the line numbers and blame text in front.
      DisableSelection {
        MdBlockGutter(blockLines, showLineNumbers.value, blame, region, commitColor)
      }
      Spacer(Modifier.width(20.dp))
      Column(Modifier.weight(1f)) {
        super.RenderBlock(locatable.originalBlock, enabled, onUrlClick, Modifier)
      }
    }
  }
}

/** The width of the line-number column, wide enough for five digits at 11 sp. */
private val GUTTER_WIDTH = 36.dp

/** The width of the blame column, wide enough for a name and a relative date at 11 sp. */
private val BLAME_WIDTH = 112.dp

/**
 * The gutter of one block: the blame text of the newest change and the line number.
 *
 * The uncommitted-change stripe and the author color band are not drawn here: they span the whole
 * block behind the item row, like the editor gutter bands. Blame text shows only where a commit
 * chunk starts, so one chunk reads as one region.
 */
@Composable
private fun MdBlockGutter(
  lines: IntRange,
  showNumber: Boolean,
  blame: MdBlameState,
  region: MdBlameRegion?,
  commitColor: Color,
) {
  val info = region?.blame

  Row(verticalAlignment = Alignment.Top) {
    if (blame.active) {
      Column(Modifier.width(BLAME_WIDTH + 8.dp).padding(horizontal = 4.dp)) {
        if (info != null && region.startsChunk) {
          val infoForeground = retrieveColorOrUnspecified("Component.infoForeground")
          Text(
            info.author?.let { author -> if (author.length > 12) author.take(12) + "…" else author } ?: "未提交",
            maxLines = 1,
            style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp, color = infoForeground),
          )
          if (info.time > 0) {
            Text(
              relativeTime(info.time),
              maxLines = 1,
              style =
                JewelTheme.defaultTextStyle.copy(
                  fontSize = 11.sp,
                  color = if (commitColor != Color.Unspecified) commitColor else infoForeground,
                ),
            )
          }
        }
      }
      Spacer(Modifier.width(10.dp))
    }
    if (showNumber) {
      Text(
        (lines.first + 1).toString(),
        modifier = Modifier.width(GUTTER_WIDTH),
        style = JewelTheme.defaultTextStyle.copy(
          fontSize = 11.sp,
          color = lineNumbersColor(),
          textAlign = TextAlign.Right,
        ),
      )
    }
  }
}

@Composable
private fun lineNumbersColor(): Color {
  return remember(JewelTheme.instanceUuid) {
    val fromScheme = EditorColorsManager.getInstance().globalScheme.getColor(EditorColors.LINE_NUMBERS_COLOR)
    fromScheme?.toComposeColor() ?: retrieveColorOrUnspecified("Component.infoForeground")
  }
}

/**
 * The editor scheme's own change colors, the same keys the editor gutter marker uses. The fixed
 * pairs only serve as a fallback while a theme leaves a key unset.
 */
@Composable
private fun statusColor(type: Byte): Color {
  return remember(JewelTheme.instanceUuid, type) {
    val scheme = EditorColorsManager.getInstance().globalScheme
    val key =
      when (type) {
        Range.INSERTED -> EditorColors.ADDED_LINES_COLOR
        Range.DELETED -> EditorColors.DELETED_LINES_COLOR
        Range.EQUAL -> EditorColors.WHITESPACES_MODIFIED_LINES_COLOR
        else -> EditorColors.MODIFIED_LINES_COLOR
      }
    val fallback =
      when (type) {
        Range.INSERTED -> JBColor(java.awt.Color(0x1F883D), java.awt.Color(0x3FB950))
        Range.DELETED -> JBColor(java.awt.Color(0xCF222E), java.awt.Color(0xF85149))
        else -> JBColor(java.awt.Color(0x0969DA), java.awt.Color(0x4493F8))
      }
    scheme.getColor(key)?.toComposeColor() ?: fallback.toComposeColor()
  }
}
