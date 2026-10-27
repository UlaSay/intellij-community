// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.layout

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class BasicTableLayoutTest {
    @Test
    public fun `intrinsic widths are kept when all columns fit`() {
        val widths = distributeColumnWidths(intrinsicColumnWidths = intArrayOf(100, 200), minimumColumnWidths = intArrayOf(30, 40), availableWidthForColumns = 500)

        assertArrayEquals(intArrayOf(100, 200), widths)
    }

    @Test
    public fun `every column keeps at least its minimum width when space runs out`() {
        // A CJK-heavy table: one narrow column that cannot shrink, two wide ones that can wrap
        val intrinsic = intArrayOf(20, 200, 1000)
        val minimum = intArrayOf(20, 20, 20)

        val widths = distributeColumnWidths(intrinsic, minimum, availableWidthForColumns = 500)

        for (i in widths.indices) {
            assertTrue("column $i is narrower than its minimum", widths[i] >= minimum[i])
        }
        assertTrue("columns exceed the available width", widths.sum() <= 500)
        // A column with no shrinkable surplus must not lose any width: its glyphs would be clipped
        assertEquals(20, widths[0])
        // The widest surplus takes the largest share of the space that is left
        assertTrue(widths[2] > widths[1])
    }

    @Test
    public fun `minimum widths shrink proportionally when they do not fit`() {
        val widths = distributeColumnWidths(intrinsicColumnWidths = intArrayOf(400, 400), minimumColumnWidths = intArrayOf(300, 300), availableWidthForColumns = 300)

        assertArrayEquals(intArrayOf(150, 150), widths)
    }
}
