# Markdown Light Preview

A markdown preview for the dev IDE, rendered with the platform Jewel Compose renderer
(`platform/jewel/markdown/`). No JCEF, no browser.

## What it renders

- Headings, lists, emphasis, links, block quotes
- Code blocks colored by the IDE syntax engine
- GitHub-style tables, alerts, strikethrough, and images (resolved relative to the file)
- Colors that follow the active IDE theme

It does not render mermaid diagrams.

## Build and install

Close the dev IDE, then run `build.cmd` from this directory. The script builds with Bazel,
copies the jar into the sandbox plugin directory `out/dev-data/idea_community/config/plugins/`,
and keeps the Compose UI plugin enabled in the sandbox. Restart the IDE after it.

The plugin requires the Compose UI plugin (`com.intellij.modules.compose`) through its module
dependencies. Without it the plugin does not load.

## Use

Open a `.md` file. The view starts in preview mode. Use the `预览` / `编辑` buttons to switch
between preview and text. Typing refreshes the preview after a short delay.
