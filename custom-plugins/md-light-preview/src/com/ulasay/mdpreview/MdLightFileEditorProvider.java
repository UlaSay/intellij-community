package com.ulasay.mdpreview;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Provides the light markdown editor instead of the plain text editor.
 */
public final class MdLightFileEditorProvider implements FileEditorProvider, DumbAware {
  public static final String EDITOR_TYPE_ID = "ulasay-md-light-editor";

  @Override
  public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
    String extension = file.getExtension();
    return extension != null && (extension.equalsIgnoreCase("md") || extension.equalsIgnoreCase("markdown"));
  }

  @Override
  public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
    return new MdLightFileEditor(project, file);
  }

  @Override
  public @NotNull String getEditorTypeId() {
    return EDITOR_TYPE_ID;
  }

  @Override
  public @NotNull FileEditorPolicy getPolicy() {
    return FileEditorPolicy.HIDE_DEFAULT_EDITOR;
  }
}
