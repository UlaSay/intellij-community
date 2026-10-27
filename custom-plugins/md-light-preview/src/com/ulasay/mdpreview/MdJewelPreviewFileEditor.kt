package com.ulasay.mdpreview

import com.intellij.diagnostic.rethrowControlFlowException
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.Nls
import java.beans.PropertyChangeListener
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.Timer

/**
 * The preview half of the split editor: the Jewel pane fed by the document text.
 *
 * The document listener keeps the preview in sync while the text half of the split is edited.
 */
internal class MdJewelPreviewFileEditor(project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
  private val document: Document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
  private val previewPane = MdJewelPreviewPane(project, rootDir())
  private val debounce = Timer(250) { previewPane.update(document.text) }

  private val documentListener = object : DocumentListener {
    override fun documentChanged(event: DocumentEvent) {
      debounce.restart()
    }
  }

  init {
    document.addDocumentListener(documentListener)
    debounce.isRepeats = false
    previewPane.update(document.text)
  }

  private fun rootDir(): Path? {
    return try {
      file.toNioPath().parent
    }
    catch (e: RuntimeException) {
      rethrowControlFlowException(e)
      null
    }
  }

  override fun getComponent(): JComponent = previewPane

  override fun getPreferredFocusedComponent(): JComponent = previewPane

  override fun getName(): @Nls String = "Markdown Preview"

  override fun setState(state: FileEditorState) {
  }

  override fun isModified(): Boolean = false

  override fun isValid(): Boolean = file.isValid

  override fun addPropertyChangeListener(listener: PropertyChangeListener) {
  }

  override fun removePropertyChangeListener(listener: PropertyChangeListener) {
  }

  override fun dispose() {
    debounce.stop()
    document.removeDocumentListener(documentListener)
  }
}
