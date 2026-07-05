# Sirentide Quickstart 🌊

Turn a tiny diagram DSL into a self-contained SVG, then drop it straight into your HTML.

> **Status note.** Sirentide is *very* early (M1 in progress). What's **built**: the render
> pipeline (DSL → IR → layout → SVG), the clean-room **font-metrics oracle**, and the first
> diagram type — **`pie`**. What's **planned** (each flagged inline): `xychart`, a minimal
> `sequence` with play-through, text labels as paths, the native effect layer, and the
> LatteX-math-in-labels composition. Accuracy over hype: if it isn't built, this doc says so.

---

## 1. What Sirentide is

Sirentide is a clean-room, pure-**Java 25**, **zero-runtime-dependency** renderer that turns a
small diagram DSL into **static SVG at build time** — no JavaScript, no headless browser, no
runtime. Its emitter targets a deliberately tiny, sanitizer-safe SVG alphabet (`<svg>`, `<g>`,
`<path>`, and geometry — never `<script>`, `<style>`, `<foreignObject>`, or external `href`s),
so the output is safe to inline directly into HTML and already sits inside a standard sanitizer
allow-list. Apache-2.0.

It's the diagram sibling of [LatteX](https://github.com/supsup/LatteX) (the LaTeX→SVG math
renderer), and shares its discipline — and, soon, its font, so a diagram label can *contain* a
real LaTeX formula.

## 2. Render a diagram

The whole pipeline is one call. Give it a Sirentide DSL source, get back a self-contained SVG
string:

```java
import com.sirentide.api.Sirentide;

String svg = Sirentide.render("""
    pie
      "Reviews" : 40
      "Builds"  : 30
      "Docs"    : 30
    """);
// -> <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 240 240"> <path .../> ... </svg>
```

That SVG is complete and standalone — write it to a `.svg` file, or paste it inline into a page.
A malformed row is skipped and an unknown diagram type degrades to an empty shell — the bake
never fails on one bad line.

## 3. From the command line

```bash
echo 'pie
  "A" : 60
  "B" : 40' | java -jar build/libs/sirentide-0.1.0.jar
```

Reads a DSL from stdin, writes the SVG to stdout. *(Planned: `--batch` for many diagrams per
invocation, one JVM.)*

## 4. Why it's safe to inline

Sirentide emits only inert geometry — filled `<path>`s, no script, no style, no runtime. So the
baked SVG needs **no downstream sanitization** and ships **no JavaScript**. For a static docs
site baking untrusted-author content, that's the whole point.

## 5. Status legend

| Mark | Meaning |
|---|---|
| **built** | Works today: the pipeline, the font-metrics oracle, `pie`. |
| *planned* | Designed, not yet built: `xychart`, `sequence` + play-through, text-as-paths labels, the native effect layer, LatteX-math-in-labels. |

See [`docs/DESIGN.md`](docs/DESIGN.md) for the full design and [`SLOWSTART.md`](SLOWSTART.md) for
the why.
