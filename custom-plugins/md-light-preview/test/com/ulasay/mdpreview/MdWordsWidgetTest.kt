package com.ulasay.mdpreview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class MdWordsWidgetTest {
  @Test
  fun `each latin word counts once`() {
    assertEquals(3, countWords("one two, three."))
  }

  @Test
  fun `a word keeps its inner apostrophe, hyphen, and underscore`() {
    assertEquals(2, countWords("don't stop_now"))
  }

  @Test
  fun `each CJK character counts as one word`() {
    assertEquals(4, countWords("你好世界"))
  }

  @Test
  fun `latin words and CJK characters add up`() {
    assertEquals(3, countWords("中文 word"))
  }

  @Test
  fun `text without words counts zero`() {
    assertEquals(0, countWords(" \t\n，。！？"))
  }
}
