# Markdown Light Preview

A lightweight markdown preview for the dev IDE. The default engine renders with the platform
Jewel Compose renderer (`platform/jewel/markdown/`); a Swing HTML engine is the fallback when
the Compose UI plugin is not available.

## What it renders

- Headings, lists, emphasis, links, block quotes
- Code blocks with IDE syntax coloring (Jewel engine) or regex coloring (Swing engine)
- GitHub-style tables, strikethrough, alerts, and images (Jewel engine)
- Colors that follow the active IDE theme

It does not render mermaid diagrams or full CSS.

## Build and install

Close the dev IDE, then run `build.cmd` from this directory. The script builds with Bazel,
copies the jar into the sandbox plugin directory `out/dev-data/idea_community/config/plugins/`,
and re-enables the Compose UI plugin if it was trimmed. Restart the IDE after it.

## Use

Open a `.md` file. The view starts in preview mode. Use the `预览` / `编辑` buttons to switch
between preview and text. When both engines are available, the `引擎: Swing / Jewel` buttons in
the top-right corner switch the preview engine; the choice is remembered.

The `Light Markdown Preview` action (`Alt+Shift+P`) still opens the Swing preview dialog.
