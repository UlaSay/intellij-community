package com.ulasay.mdpreview

import com.intellij.openapi.vcs.ex.Range
import org.jetbrains.jewel.markdown.InlineMarkdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.scrolling.ScrollingSynchronizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class MdPreviewBlameTest {
  private fun block(firstLine: Int, lastLine: Int) =
    ScrollingSynchronizer.LocatableMarkdownBlock(MarkdownBlock.Paragraph(InlineMarkdown.Text("text")), firstLine..lastLine)

  @Test
  fun `no loaded blame gives no regions`() {
    assertTrue(buildBlameRegions(listOf(block(0, 1)), data = null).isEmpty())
  }

  @Test
  fun `the lookup receives the source lines of the block`() {
    var asked: IntRange? = null

    val regions = buildBlameRegions(listOf(block(3, 5))) { lines ->
      asked = lines
      null
    }

    assertEquals(3..5, asked)
    assertNull(regions.values.single())
  }

  @Test
  fun `consecutive blocks of one commit form a single chunk`() {
    val blocks = listOf(block(0, 1), block(3, 4), block(6, 7))
    val change = MdBlockBlame("Ada", 10L, rank = 0)

    val regions = buildBlameRegions(blocks) { change }

    assertEquals(listOf(true, false, false), blocks.map { checkNotNull(regions[it]).startsChunk })
    assertEquals(listOf(change, change, change), blocks.map { regions[it]?.blame })
  }

  @Test
  fun `a new commit starts a new chunk`() {
    val ranks = listOf(0, 0, 1, 0)
    val blocks = ranks.indices.map { block(2 * it, 2 * it) }

    val regions = buildBlameRegions(blocks) { lines -> MdBlockBlame("Ada", 10L, rank = ranks[lines.first / 2]) }

    assertEquals(listOf(true, false, true, true), blocks.map { checkNotNull(regions[it]).startsChunk })
  }

  @Test
  fun `a block outside the annotated lines gets a null region`() {
    val annotated = block(0, 1)
    val untracked = block(3, 4)

    val regions = buildBlameRegions(listOf(annotated, untracked)) { lines ->
      if (lines.first == 0) MdBlockBlame("Ada", 10L, rank = 0) else null
    }

    assertTrue(regions[annotated] != null)
    assertNull(regions[untracked])
  }

  @Test
  fun `a block without a source range gets a null region`() {
    val plain = MarkdownBlock.Paragraph(InlineMarkdown.Text("text"))

    val regions = buildBlameRegions(listOf(plain)) { MdBlockBlame("Ada", 10L, rank = 0) }

    assertNull(regions[plain])
  }

  @Test
  fun `a change under a minute old reads as just now`() {
    assertEquals("刚刚", relativeTime(System.currentTimeMillis() - 30_000))
  }

  @Test
  fun `an older change names its unit in Chinese`() {
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    val now = System.currentTimeMillis()

    assertEquals("5 分钟前", relativeTime(now - 5 * minute))
    assertEquals("3 小时前", relativeTime(now - 3 * hour))
    assertEquals("2 天前", relativeTime(now - 2 * day))
    assertEquals("2 个月前", relativeTime(now - 65 * day))
    assertEquals("2 年前", relativeTime(now - 800 * day))
  }

  @Test
  fun `a document without a tracker has no ranges`() {
    assertTrue(rangesForBlock(0..3, MdLineStatusState()).isEmpty())
  }

  @Test
  fun `an unchanged block has no ranges`() {
    assertTrue(rangesForBlock(0..4) { null }.isEmpty())
  }

  @Test
  fun `ranges come in the gutter order`() {
    val inserted = Range(0, 1, 0, 0)
    val modified = Range(2, 4, 2, 3)
    val deleted = Range(5, 5, 5, 6)
    assertEquals(Range.INSERTED, inserted.type)
    assertEquals(Range.MODIFIED, modified.type)
    assertEquals(Range.DELETED, deleted.type)
    val byLine = mapOf(4 to deleted, 0 to inserted, 2 to modified)

    assertEquals(listOf(inserted, modified, deleted), rangesForBlock(0..4) { byLine[it] })
  }

  @Test
  fun `one deleted range is listed once for all its lines`() {
    val deleted = Range(3, 3, 3, 6)

    assertEquals(listOf(deleted), rangesForBlock(0..4) { deleted })
  }
}
