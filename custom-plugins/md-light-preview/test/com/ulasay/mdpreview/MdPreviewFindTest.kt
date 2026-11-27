package com.ulasay.mdpreview

import org.jetbrains.jewel.markdown.InlineMarkdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.scrolling.ScrollingSynchronizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class MdPreviewFindTest {
  private fun paragraph(vararg inlines: InlineMarkdown) = MarkdownBlock.Paragraph(inlines.toList())

  @Test
  fun `an empty query matches nothing`() {
    assertTrue(findMatchedBlocks(listOf(paragraph(InlineMarkdown.Text("alpha"))), "").isEmpty())
  }

  @Test
  fun `matching ignores case`() {
    assertEquals(listOf(0), findMatchedBlocks(listOf(paragraph(InlineMarkdown.Text("Alpha Beta"))), "ALPHA beta"))
  }

  @Test
  fun `a block is listed once for each occurrence`() {
    val blocks = listOf(paragraph(InlineMarkdown.Text("a a")), paragraph(InlineMarkdown.Text("b a")))

    assertEquals(listOf(0, 0, 1), findMatchedBlocks(blocks, "a"))
  }

  @Test
  fun `a wrapped block is searched through its original block`() {
    val wrapped = ScrollingSynchronizer.LocatableMarkdownBlock(paragraph(InlineMarkdown.Text("hidden token")), 0..0)

    assertEquals(listOf(0), findMatchedBlocks(listOf(wrapped), "token"))
  }

  @Test
  fun `text inside emphasis, links, and inline code is searched`() {
    val block = paragraph(
      InlineMarkdown.Emphasis("*", InlineMarkdown.Text("emph")),
      InlineMarkdown.Link("https://example.com", null, InlineMarkdown.Text("link")),
      InlineMarkdown.Code("code"),
      InlineMarkdown.Text(" and text"),
    )

    for (token in listOf("emph", "link", "code", "text")) {
      assertEquals(listOf(0), findMatchedBlocks(listOf(block), token), "'$token' is not found")
    }
  }

  @Test
  fun `the children of list items and quotes are searched`() {
    val item = MarkdownBlock.ListItem(MarkdownBlock.Paragraph(InlineMarkdown.Text("item")))
    val quote = MarkdownBlock.BlockQuote(MarkdownBlock.Paragraph(InlineMarkdown.Text("quoted")))
    val blocks = listOf(MarkdownBlock.ListBlock.UnorderedList(isTight = true, marker = "-", item), quote)

    assertEquals(listOf(0), findMatchedBlocks(blocks, "item"))
    assertEquals(listOf(1), findMatchedBlocks(blocks, "quoted"))
  }

  @Test
  fun `the content of fenced and indented code blocks is searched`() {
    val fenced = MarkdownBlock.CodeBlock.FencedCodeBlock("val token = 1", "kotlin")
    val indented = MarkdownBlock.CodeBlock.IndentedCodeBlock("token")

    assertEquals(listOf(0), findMatchedBlocks(listOf(fenced), "token"))
    assertEquals(listOf(0), findMatchedBlocks(listOf(indented), "token"))
  }

  @Test
  fun `a soft line break joins the words of a paragraph`() {
    val block = paragraph(InlineMarkdown.Text("line"), InlineMarkdown.SoftLineBreak, InlineMarkdown.Text("break"))

    assertEquals(listOf(0), findMatchedBlocks(listOf(block), "line break"))
  }
}
