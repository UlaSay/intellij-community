package com.ulasay.mdpreview;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.diagnostic.Logger;
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
import com.intellij.ui.components.JBLabel;
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
import java.nio.file.Path;

import static com.intellij.diagnostic.ControlFlowExceptionsKt.rethrowControlFlowException;

/**
 * The file editor for markdown: a preview-first view with an edit toggle.
 * The preview runs on the Jewel Compose renderer, or on the Swing HTML engine
 * when the Compose UI plugin is not available.
 */
final class MdLightFileEditor extends UserDataHolderBase implements FileEditor {
  private static final String PREVIEW_CARD = "preview";
  private static final String EDIT_CARD = "edit";
  private static final String ENGINE_PROPERTY = "ulasay.md-preview.engine";
  private static final String ENGINE_SWING = "swing";
  private static final String ENGINE_JEWEL = "jewel";
  private static final boolean JEWEL_AVAILABLE = probeJewel();
  private static final Logger LOG = Logger.getInstance(MdLightFileEditor.class);

  private final Project project;
  private final VirtualFile file;
  private final Document document;
  private final Editor sourceEditor;
  private final JEditorPane previewPane = new JEditorPane();
  private final JPanel previewHost = new JPanel(new BorderLayout());
  private final JPanel center = new JPanel(new CardLayout());
  private final JPanel root = new JPanel(new BorderLayout());
  private final Timer debounce;
  private final DocumentListener documentListener = new DocumentListener() {
    @Override
    public void documentChanged(@NotNull DocumentEvent event) {
      debounce.restart();
    }
  };

  private String engine = initialEngine();
  private MdJewelPreviewPane jewelPane;

  MdLightFileEditor(@NotNull Project project, @NotNull VirtualFile file) {
    this.project = project;
    this.file = file;
    this.document = FileDocumentManager.getInstance().getDocument(file);
    this.sourceEditor = EditorFactory.getInstance().createEditor(document, project);
    this.debounce = new Timer(250, event -> refreshPreview());

    MdHtml.install(previewPane);
    applyEngine();

    center.add(previewHost, PREVIEW_CARD);
    center.add(sourceEditor.getComponent(), EDIT_CARD);

    var previewButton = new JToggleButton("预览", true);
    var editButton = new JToggleButton("编辑", false);
    var group = new ButtonGroup();
    group.add(previewButton);
    group.add(editButton);
    previewButton.addActionListener(event -> showCard(PREVIEW_CARD));
    editButton.addActionListener(event -> showCard(EDIT_CARD));

    var viewButtons = new JPanel();
    viewButtons.add(previewButton);
    viewButtons.add(editButton);
    viewButtons.setBorder(JBUI.Borders.empty(2, 4));

    var toolbar = new JPanel(new BorderLayout());
    toolbar.add(viewButtons, BorderLayout.WEST);
    var engineButtons = createEngineButtons();
    if (engineButtons != null) {
      toolbar.add(engineButtons, BorderLayout.EAST);
    }

    root.add(toolbar, BorderLayout.NORTH);
    root.add(center, BorderLayout.CENTER);

    document.addDocumentListener(documentListener);
    debounce.setRepeats(false);
    LOG.info("md editor created, jewelAvailable=" + JEWEL_AVAILABLE + ", engine=" + engine);
    refreshPreview();
  }

  private static boolean probeJewel() {
    ClassLoader loader = MdLightFileEditor.class.getClassLoader();
    String[] probes = {
      // Top-level Kotlin declarations compile to *Kt file classes; probe those, not the declaration names.
      "org.jetbrains.jewel.bridge.JewelComposePanelWrapperKt",
      "org.jetbrains.jewel.markdown.processing.MarkdownProcessor",
      "org.jetbrains.jewel.intui.markdown.bridge.BridgeProvideMarkdownStylingKt",
      "coil3.compose.LocalPlatformContext_nonAndroidKt",
      "org.jetbrains.jewel.markdown.extensions.github.tables.GitHubTableProcessorExtension",
    };
    for (String probe : probes) {
      try {
        Class.forName(probe, false, loader);
      }
      catch (Throwable t) {
        rethrowControlFlowException(t);
        Logger.getInstance(MdLightFileEditor.class).warn("Jewel probe failed on " + probe + " (loader " + loader + ")", t);
        return false;
      }
    }
    Logger.getInstance(MdLightFileEditor.class).info("Jewel probe OK (loader " + loader + ")");
    return true;
  }

  private static String initialEngine() {
    if (!JEWEL_AVAILABLE) {
      return ENGINE_SWING;
    }
    return ENGINE_SWING.equals(PropertiesComponent.getInstance().getValue(ENGINE_PROPERTY)) ? ENGINE_SWING : ENGINE_JEWEL;
  }

  private @Nullable JComponent createEngineButtons() {
    if (!JEWEL_AVAILABLE) {
      return null;
    }
    var swingButton = new JToggleButton("Swing", ENGINE_SWING.equals(engine));
    var jewelButton = new JToggleButton("Jewel", ENGINE_JEWEL.equals(engine));
    var group = new ButtonGroup();
    group.add(swingButton);
    group.add(jewelButton);
    swingButton.addActionListener(event -> switchEngine(ENGINE_SWING));
    jewelButton.addActionListener(event -> switchEngine(ENGINE_JEWEL));

    var engineButtons = new JPanel();
    engineButtons.add(new JBLabel("引擎:"));
    engineButtons.add(swingButton);
    engineButtons.add(jewelButton);
    engineButtons.setBorder(JBUI.Borders.empty(2, 4));
    return engineButtons;
  }

  private void switchEngine(@NotNull String newEngine) {
    if (newEngine.equals(engine)) {
      return;
    }
    engine = newEngine;
    PropertiesComponent.getInstance().setValue(ENGINE_PROPERTY, newEngine);
    applyEngine();
  }

  private void applyEngine() {
    previewHost.removeAll();
    if (ENGINE_JEWEL.equals(engine)) {
      previewHost.add(jewelPane(), BorderLayout.CENTER);
    }
    else {
      previewHost.add(new JBScrollPane(previewPane), BorderLayout.CENTER);
    }
    previewHost.revalidate();
    previewHost.repaint();
    refreshPreview();
  }

  private @NotNull MdJewelPreviewPane jewelPane() {
    if (jewelPane == null) {
      jewelPane = new MdJewelPreviewPane(project, rootDir());
    }
    return jewelPane;
  }

  private @Nullable Path rootDir() {
    try {
      return file.toNioPath().getParent();
    }
    catch (RuntimeException e) {
      rethrowControlFlowException(e);
      return null;
    }
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
    if (ENGINE_JEWEL.equals(engine)) {
      jewelPane().update(document.getText());
    }
    else {
      MdHtml.update(previewPane, document.getText());
    }
  }

  @Override
  public @NotNull JComponent getComponent() {
    return root;
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    return ENGINE_JEWEL.equals(engine) ? jewelPane() : previewPane;
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
