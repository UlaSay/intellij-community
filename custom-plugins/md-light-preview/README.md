# Markdown Light Preview

A markdown preview for the IDE, rendered with the platform Jewel Compose renderer
(`platform/jewel/markdown/`). No JCEF, no browser.

The product bundles the plugin. It ships in the distribution under `plugins/md-light-preview`.
The plugin requires the Compose UI plugin (`com.intellij.modules.compose`) through its module
dependencies. Without it the plugin does not load.

## What it renders

- Headings, lists, emphasis, links, block quotes
- Code blocks colored by the IDE syntax engine
- GitHub-style tables, alerts, strikethrough, and images (resolved relative to the file)
- Colors that follow the active IDE theme
- A source gutter with line numbers, git blame, and stripes for uncommitted changes
- A find bar and a word count on the status bar

It does not render mermaid diagrams.

## Use

Open a `.md` file. The editor is a split view built on the platform `TextEditorWithPreview`
skeleton: the text editor on the left, the Jewel preview on the right, with the standard
three-state switcher (editor only / split / preview only) and a draggable divider. The chosen
layout persists. Typing refreshes the preview after a short delay.

- The pills at the top right of the preview turn the blame gutter and the line numbers on or off.
- Ctrl+F with the focus inside the preview opens the find bar of the preview. Enter jumps to the
  next match, Shift+Enter to the previous one, and Escape closes the bar.
- A click on a change stripe opens the platform change popup.

## Build

The plugin is not in the JPS model. Bazel builds it from the target
`//custom-plugins/md-light-preview`.

To put a change into the IDE, close the dev IDE and run `custom-plugins/install-all.cmd` from the
repository root. The script rebuilds the distribution with the plugin. Then start the IDE again.

## Test

Run the unit tests from the repository root:

```
./tools/bt.cmd //custom-plugins/md-light-preview:md_light_preview_test --filter com.ulasay.mdpreview
```

In Git Bash, set `MSYS_NO_PATHCONV=1` first.
