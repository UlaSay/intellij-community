package com.ulasay.mdpreview

import com.intellij.DynamicBundle
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.EditorBasedStatusBarPopup
import com.intellij.openapi.wm.impl.status.widget.StatusBarEditorBasedWidgetFactory
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.MdPreviewBundle"

/** Strings of the plugin. */
internal class MdPreviewBundle : DynamicBundle(BUNDLE) {
  companion object {
    private val INSTANCE: MdPreviewBundle = MdPreviewBundle()

    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): @Nls String =
      INSTANCE.getMessage(key, *params)
  }
}

/** Counts the words of a markdown document. Each CJK ideograph counts as one word. */
internal fun countWords(text: String): Int {
  // Latin words and CJK characters have disjoint ranges, so both scans count every unit once.
  return WORD.findAll(text).count() + CJK_CHAR.findAll(text).count()
}

private val WORD = Regex("[A-Za-z0-9][A-Za-z0-9'’_-]*")
private val CJK_CHAR = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\u3040-\\u30FF\\u31F0-\\u31FF\\uAC00-\\uD7AF]")

internal fun isMarkdownFile(file: VirtualFile?): Boolean {
  val extension = file?.extension ?: return false
  return extension.equals("md", ignoreCase = true) || extension.equals("markdown", ignoreCase = true)
}

/** Adds the word count of the open markdown document to the status bar. */
class MdWordsWidgetFactory : StatusBarEditorBasedWidgetFactory() {
  override fun getId(): String = MdWordsWidget.WIDGET_ID

  override fun getDisplayName(): @Nls String = MdPreviewBundle.message("words.widget.name")

  override fun createWidget(project: Project): StatusBarWidget = MdWordsWidget(project)
}

internal class MdWordsWidget(project: Project) : EditorBasedStatusBarPopup(project, isWriteableFileRequired = false) {
  override fun ID(): String = WIDGET_ID

  override fun getWidgetState(file: VirtualFile?): WidgetState {
    if (!isMarkdownFile(file)) return WidgetState.HIDDEN
    val document = FileDocumentManager.getInstance().getDocument(file!!) ?: return WidgetState.HIDDEN
    val words = countWords(document.text)
    return WidgetState(
      toolTip = MdPreviewBundle.message("words.widget.tooltip"),
      text = MdPreviewBundle.message("words.count", words),
      isActionEnabled = false,
    )
  }

  override fun createPopup(context: DataContext): ListPopup? = null

  override fun createInstance(project: Project): StatusBarWidget = MdWordsWidget(project)

  companion object {
    const val WIDGET_ID: String = "ulasay.md.words"
  }
}
