package org.jetbrains.jewel.foundation.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import org.jetbrains.jewel.foundation.modifier.thenIf

/**
 * A simple table that sizes columns to take as much room as they need. If the horizontal space available is less than
 * what the cells would take, every column keeps at least the width of its widest unbreakable content unit, and the
 * remaining space is shared proportionally to how much each column wanted beyond that minimum.
 *
 * Cells **must** only contain one top-level component. If you need your cells to contain more than one, wrap your cell
 * content in a [`Box`][Box], [`Column`][androidx.compose.foundation.layout.Column],
 * [`Row`][androidx.compose.foundation.layout.Row], etc.
 *
 * Incoming height constraints are ignored. The table will always take up as much vertical room as it needs. If you want
 * to constrain the table height consider wrapping it in a
 * [`VerticallyScrollableContainer`][org.jetbrains.jewel.ui.component.VerticallyScrollableContainer].
 *
 * @param rowCount The number of rows this table has.
 * @param columnCount The number of columns this table has.
 * @param cellBorderColor The color of the cell borders. Set to [Color.Unspecified] to avoid drawing the borders — in
 *   which case, the [cellBorderWidth] acts as a padding.
 * @param modifier Modifier to apply to the table.
 * @param cellBorderWidth The width of the table's borders.
 * @param rows The rows that make up the table. Each row is a list of composables, one per row cell.
 */
@Suppress("KDocUnresolvedReference", "ComposableParamOrder")
@Composable
public fun BasicTableLayout(
    rowCount: Int,
    columnCount: Int,
    cellBorderColor: Color,
    modifier: Modifier = Modifier,
    cellBorderWidth: Dp = 1.dp,
    rows: List<List<@Composable () -> Unit>>,
) {
    var rowHeights by remember { mutableStateOf(emptyList<Int>()) }
    var columnWidths by remember { mutableStateOf(emptyList<Int>()) }

    Layout(
        modifier =
            modifier.thenIf(rowHeights.size == rowCount && columnWidths.size == columnCount) {
                drawTableBorders(cellBorderColor, cellBorderWidth, rowHeights, columnWidths)
            },
        content = { rows.forEach { row -> row.forEach { cell -> cell() } } },
        measurePolicy = { measurables, incomingConstraints ->
            require(rows.size == rowCount) { "Found ${rows.size} rows, but expected $rowCount." }
            require(measurables.size == rowCount * columnCount) {
                "Found ${measurables.size} cells, but expected ${rowCount * columnCount}."
            }

            val measuredIntrinsicColumnWidths = IntArray(columnCount)
            val minimumColumnWidths = IntArray(columnCount)
            val measurablesByRow = measurables.chunked(columnCount)
            for ((rowIndex, rowCells) in rows.withIndex()) {
                require(rowCells.size == columnCount) {
                    "Row $rowIndex contains ${rowCells.size} cells, but it should have $columnCount cells."
                }

                for ((columnIndex, _) in rowCells.withIndex()) {
                    // Measure each cell individually
                    val measurable = measurablesByRow[rowIndex][columnIndex]

                    // Store the intrinsic width for each column, assuming we have infinite
                    // vertical space available to display each cell (which we do)
                    val intrinsicCellWidth = measurable.maxIntrinsicWidth(height = Int.MAX_VALUE)
                    measuredIntrinsicColumnWidths[columnIndex] =
                        max(measuredIntrinsicColumnWidths[columnIndex].or(0), intrinsicCellWidth)

                    // The minimum width keeps the widest unbreakable content unit, for example a single CJK
                    // character or a whole latin word, fully visible when space runs out
                    val minimumCellWidth = measurable.minIntrinsicWidth(height = Int.MAX_VALUE)
                    minimumColumnWidths[columnIndex] =
                        max(minimumColumnWidths[columnIndex].or(0), minimumCellWidth)
                }
            }

            // The available width we can assign to cells is equal to the max width from the
            // incoming
            // constraints, minus the vertical borders applied between columns and to the sides of
            // the
            // table
            val cellBorderWidthPx = cellBorderWidth.roundToPx()
            val totalHorizontalBordersWidth = cellBorderWidthPx * (columnCount + 1)
            val availableWidth = incomingConstraints.maxWidth
            val availableWidthForColumns = availableWidth - totalHorizontalBordersWidth

            // We want to size the columns as their intrinsic width if they all fit, or narrower
            // when there is not enough room to show them all
            val intrinsicColumnWidths =
                distributeColumnWidths(measuredIntrinsicColumnWidths, minimumColumnWidths, availableWidthForColumns)
            val tableWidth = intrinsicColumnWidths.sum() + totalHorizontalBordersWidth
            columnWidths = intrinsicColumnWidths.toList()

            // The height of each row is the maximum intrinsic height of their cells, calculated
            // from the (possibly scaled) intrinsic column widths we just computed
            val intrinsicRowHeights = IntArray(rowCount)
            var tableHeight = 0
            measurablesByRow.mapIndexed { rowIndex, rowMeasurables ->
                var maxCellHeight = 0
                for ((columnIndex, cellMeasurable) in rowMeasurables.withIndex()) {
                    val columnWidth = columnWidths[columnIndex]
                    val cellHeight = cellMeasurable.maxIntrinsicHeight(width = columnWidth)
                    maxCellHeight = max(maxCellHeight, cellHeight)
                }

                tableHeight += maxCellHeight
                intrinsicRowHeights[rowIndex] = maxCellHeight
            }
            rowHeights = intrinsicRowHeights.toList()

            // Add the horizontal borders drawn between rows and on top and bottom of the table
            tableHeight += cellBorderWidthPx * (rowCount + 1)

            // Measure all cells, using the fixed constraints we calculated for each row and column
            val placeables =
                measurables.chunked(columnCount).mapIndexed { rowIndex, cellMeasurables ->
                    cellMeasurables.mapIndexed { columnIndex, cellMeasurable ->
                        val cellConstraints = Constraints.fixed(columnWidths[columnIndex], rowHeights[rowIndex])
                        cellMeasurable.measure(cellConstraints)
                    }
                }

            layout(tableWidth, tableHeight) {
                // Place cells. We start by leaving space for the top and start-side borders
                var y = cellBorderWidthPx

                placeables.forEachIndexed { _, cellPlaceables ->
                    var x = cellBorderWidthPx

                    var rowHeight = 0
                    cellPlaceables.forEach { cellPlaceable ->
                        cellPlaceable.placeRelative(x, y)
                        x += cellBorderWidthPx
                        x += cellPlaceable.width
                        rowHeight = cellPlaceable.height.coerceAtLeast(rowHeight)
                    }

                    y += cellBorderWidthPx
                    y += rowHeight
                }
            }
        },
    )
}

/**
 * Computes the final column widths for a table laid out in [availableWidthForColumns] pixels.
 *
 * Columns keep their intrinsic widths when they all fit. When they don't, every column first
 * keeps its minimum width — the width of its widest unbreakable content unit, so no glyph is
 * clipped — and the space that is left is shared proportionally to how much each column wanted
 * beyond its minimum. If even the minimum widths don't fit, they are shrunk proportionally as a
 * last resort, so the table still fits within the available space.
 *
 * The returned widths never add up to more than [availableWidthForColumns].
 */
internal fun distributeColumnWidths(
    intrinsicColumnWidths: IntArray,
    minimumColumnWidths: IntArray,
    availableWidthForColumns: Int,
): IntArray {
    require(intrinsicColumnWidths.size == minimumColumnWidths.size) {
        "Found ${intrinsicColumnWidths.size} intrinsic widths, but ${minimumColumnWidths.size} minimum widths."
    }

    val minTableWidth = minimumColumnWidths.sum()
    if (intrinsicColumnWidths.sum() <= availableWidthForColumns) {
        // There is enough room for all columns, use the intrinsic column widths as they are
        return intrinsicColumnWidths.copyOf()
    }

    if (minTableWidth >= availableWidthForColumns) {
        // Not even the minimum widths fit; shrink them proportionally as a last resort
        val scaleRatio = availableWidthForColumns.toFloat() / minTableWidth
        return IntArray(minimumColumnWidths.size) { i -> (minimumColumnWidths[i] * scaleRatio).toInt() }
    }

    // Give every column its minimum width, then share the space that is left proportionally
    // to how much each column wanted beyond its minimum. Shrinking a column below its widest
    // unbreakable unit would clip its glyphs.
    val extraWidth = availableWidthForColumns - minTableWidth
    val totalShrinkableWidth =
        intrinsicColumnWidths.indices.sumOf { intrinsicColumnWidths[it] - minimumColumnWidths[it] }
    return IntArray(intrinsicColumnWidths.size) { i ->
        val shrinkableWidth = intrinsicColumnWidths[i] - minimumColumnWidths[i]
        // By truncating the decimal side, we may end up a few pixels short of the available
        // width, but we never exceed it.
        minimumColumnWidths[i] + (shrinkableWidth * extraWidth.toFloat() / totalShrinkableWidth).toInt()
    }
}

private fun Modifier.drawTableBorders(
    cellBorderColor: Color,
    cellBorderWidth: Dp,
    rowHeights: List<Int>,
    columnWidths: List<Int>,
) = drawBehind {
    val borderWidthPx = cellBorderWidth.toPx()
    val halfBorderWidthPx = borderWidthPx / 2f

    // First, draw the outer border
    drawRect(
        color = cellBorderColor,
        topLeft = Offset(halfBorderWidthPx, halfBorderWidthPx),
        size = Size(size.width - borderWidthPx, size.height - borderWidthPx),
        style = Stroke(width = borderWidthPx),
    )

    // Then, draw all horizontal borders below rows.
    // No need to draw the last horizontal border as it's covered by the border rect
    var y = halfBorderWidthPx
    val endX = size.width - borderWidthPx

    for (i in 0 until rowHeights.lastIndex) {
        y += rowHeights[i].toFloat() + borderWidthPx
        drawLine(
            color = cellBorderColor,
            start = Offset(halfBorderWidthPx, y),
            end = Offset(endX, y),
            strokeWidth = borderWidthPx,
        )
    }

    // Lastly, draw all vertical borders to the end of columns
    // (minus the last one, as before)
    var x = halfBorderWidthPx
    val endY = size.height - borderWidthPx

    for (i in 0 until columnWidths.lastIndex) {
        x += columnWidths[i].toFloat() + borderWidthPx
        drawLine(
            color = cellBorderColor,
            start = Offset(x, halfBorderWidthPx),
            end = Offset(x, endY),
            strokeWidth = borderWidthPx,
        )
    }
}
