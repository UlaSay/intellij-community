package com.ulasay.mdpreview;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public final class MdPreviewAction extends AnAction {
  private static final Set<String> MARKDOWN_EXTENSIONS = Set.of("md", "markdown");

  @Override
  public void update(@NotNull AnActionEvent event) {
    event.getPresentation().setEnabledAndVisible(markdownFile(event) != null);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent event) {
    Project project = event.getProject();
    VirtualFile file = markdownFile(event);
    Editor editor = event.getData(CommonDataKeys.EDITOR);
    if (project == null || file == null || editor == null) {
      return;
    }
    MdPreviewDialog.showFor(project, file, editor);
  }

  private static @Nullable VirtualFile markdownFile(@NotNull AnActionEvent event) {
    VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
    if (file == null) {
      return null;
    }
    String extension = file.getExtension();
    return extension != null && MARKDOWN_EXTENSIONS.contains(extension) ? file : null;
  }
}
