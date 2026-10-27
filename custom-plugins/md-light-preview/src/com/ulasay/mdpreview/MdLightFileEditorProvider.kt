package com.ulasay.mdpreview

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditorWithPreviewProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Provides the markdown editor built on the platform split skeleton.
 *
 * The skeleton supplies the text editor, the split layout, and the three-state switcher.
 * The preview half is the in-process Jewel pane; no browser takes part.
 */
class MdLightFileEditorProvider : TextEditorWithPreviewProvider(MdJewelPreviewProvider()), DumbAware {
  /**
   * The preview half, not registered on its own: only the combined split provider is,
   * so the editor switcher lists one entry.
   */
  private class MdJewelPreviewProvider : FileEditorProvider {
    override fun accept(project: Project, file: VirtualFile): Boolean {
      val extension = file.extension ?: return false
      return extension.equals("md", ignoreCase = true) || extension.equals("markdown", ignoreCase = true)
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = MdJewelPreviewFileEditor(project, file)

    override fun getEditorTypeId(): String = "ulasay-md-jewel-preview"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
  }
}
