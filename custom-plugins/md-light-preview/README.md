# Markdown Light Preview

A lightweight markdown preview for the dev IDE. It renders through the Swing HTML engine (`HTMLEditorKit`), so it needs no JCEF and no browser. The parser is `org.intellij.markdown` from the platform library `lib/intellij.libraries.markdown.jar`.

## What it renders

- Headings, lists, emphasis, links, images (as text), block quotes
- Code blocks on a colored background
- GitHub-style tables (through the GFM flavour)
- Plain colors that follow the light and dark IDE theme

It does not render mermaid diagrams or full CSS. That is the price of the small footprint.

## Build and install

Run `build.cmd` from this directory, then restart the IDE.

The script compiles with `javac` from the dev IDE JBR, packages with `jar` from a local JDK, and copies the jar into the sandbox plugin directory `out/dev-data/idea_community/config/plugins/`.

## Use

Open a `.md` file, then run `Light Markdown Preview` from the editor context menu, the View menu, or press `Alt+Shift+P`. The window follows the typing with a short delay.
