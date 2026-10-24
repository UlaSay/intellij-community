package com.ulasay.mdpreview;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.beans.PropertyChangeListener;

/**
 * The file editor for markdown: a preview-first view with an edit toggle.
 */
final class MdLightFileEditor extends UserDataHolderBase implements FileEditor {
  private static final String PREVIEW_CARD = "preview";
  private static final String EDIT_CARD = "edit";

  private final VirtualFile file;
  private final Document document;
  private final Editor sourceEditor;
  private final JEditorPane previewPane = new JEditorPane();
  private final JPanel center = new JPanel(new CardLayout());
  private final JPanel root = new JPanel(new BorderLayout());
  private final Timer debounce;
  private final DocumentListener documentListener = new DocumentListener() {
    @Override
    public void documentChanged(@NotNull DocumentEvent event) {
      debounce.restart();
    }
  };

  MdLightFileEditor(@NotNull Project project, @NotNull VirtualFile file) {
    this.file = file;
    this.document = FileDocumentManager.getInstance().getDocument(file);
    this.sourceEditor = EditorFactory.getInstance().createEditor(document, project);
    this.debounce = new Timer(250, event -> refreshPreview());

    MdHtml.install(previewPane);

    var scrollPane = new JBScrollPane(previewPane);
    center.add(scrollPane, PREVIEW_CARD);
    center.add(sourceEditor.getComponent(), EDIT_CARD);

    var previewButton = new JToggleButton("预览", true);
    var editButton = new JToggleButton("编辑", false);
    var group = new ButtonGroup();
    group.add(previewButton);
    group.add(editButton);
    previewButton.addActionListener(event -> showCard(PREVIEW_CARD));
    editButton.addActionListener(event -> showCard(EDIT_CARD));

    var buttons = new JPanel();
    buttons.add(previewButton);
    buttons.add(editButton);
    buttons.setBorder(JBUI.Borders.empty(2, 4));

    root.add(buttons, BorderLayout.NORTH);
    root.add(center, BorderLayout.CENTER);

    document.addDocumentListener(documentListener);
    debounce.setRepeats(false);
    refreshPreview();
  }

  private void showCard(@NotNull String card) {
    ((CardLayout)center.getLayout()).show(center, card);
    if (PREVIEW_CARD.equals(card)) {
      refreshPreview();
    }
    else {
      sourceEditor.getContentComponent().requestFocusInWindow();
    }
  }

  private void refreshPreview() {
    MdHtml.update(previewPane, document.getText());
  }

  @Override
  public @NotNull JComponent getComponent() {
    return root;
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    return previewPane;
  }

  @Override
  public @NotNull VirtualFile getFile() {
    return file;
  }

  @Override
  public @NotNull String getName() {
    return "Light Markdown";
  }

  @Override
  public void setState(@NotNull FileEditorState state) {
  }

  @Override
  public boolean isModified() {
    return false;
  }

  @Override
  public boolean isValid() {
    return file.isValid();
  }

  @Override
  public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
  }

  @Override
  public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
  }

  @Override
  public void dispose() {
    debounce.stop();
    document.removeDocumentListener(documentListener);
    EditorFactory.getInstance().releaseEditor(sourceEditor);
  }
}
