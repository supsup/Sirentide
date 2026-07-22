# Sirentide Quickstart 🌊

Turn a tiny diagram DSL into a self-contained SVG, then drop it straight into your HTML.

> **Status note.** Sirentide is early but real. What's **built**: the render pipeline
> (DSL → IR → layout → SVG), the clean-room **font-metrics oracle**, labels baked to `<path>`
> glyphs, and **twenty-two diagram types** — `pie`, `xychart`, `timeline`, `gantt`, `flowchart`,
> `sequence`, `state`, `quadrant`, `classDiagram`, `erDiagram`, `gitGraph`, `journey`, `mindmap`,
> `sankey`, `mathblock`, `matrix`, `heatmap`, `snake`, `tensornetwork`, `young`, `dynkin`, and `knot`. Also built: **LaTeX math in labels** (the LatteX bridge), **semantic
> anchors** (`data-sirentide-role/id/seq`), the **baked-frame play-through API** (`renderFrames`),
> `classDef`/`class` **semantic colour classes**, the `%% caption:` **annotation band**, and the
> live **`/docs`** ```` ```sirentide ```` fence. Accuracy over hype: if it isn't built, this doc
> says so.

---

## 1. What Sirentide is

Sirentide is a clean-room, pure-**Java 25**, **zero-runtime-dependency** renderer that turns a
small diagram DSL into **static SVG at build time** — no JavaScript, no headless browser, no
runtime. Its emitter targets a deliberately tiny, sanitizer-safe SVG alphabet (`<svg>`, `<path>`,
`<rect>`, `<line>`, plus `<g>` for math fragments + semantic anchors and `<title>`/`<desc>` for
a11y — labels are baked to `<path>` glyphs; never `<script>`, `<style>`, `<foreignObject>`, or
external `href`s), so the output is safe to inline directly into HTML and already sits inside a
standard sanitizer allow-list. Apache-2.0.

It's the diagram sibling of [LatteX](https://github.com/supsup/LatteX) (the LaTeX→SVG math
renderer), and shares its discipline — and its font, so a diagram label can *contain* a real
LaTeX formula, rendered at bake.

> **Label font coverage.** Labels bake to `<path>` glyphs from the bundled **STIX Two Math** font,
> which covers **Latin text and mathematical symbols**. Code points outside that coverage —
> non-Latin scripts (CJK, Arabic, …) and emoji — have no glyph and bake today as empty `.notdef`
> boxes. The bake never fails on them; it stays inert and deterministic. `renderWithDiagnostics`
> surfaces the boundary as an `OK` result whose message/detail **names the out-of-coverage
> `U+XXXX` code points**, so an unexplained box is a nameable signal rather than a silent surprise.

## 2. Render a diagram

The whole pipeline is one call. Give it a Sirentide DSL source, get back a self-contained SVG
string. The first token names the diagram type. A **pie**:

```java
import com.sirentide.api.Sirentide;

String svg = Sirentide.render("""
    pie legend
      "Reviews" : 40
      "Builds"  : 30
      "Docs"    : 30 #22c55e
    """);
// -> <svg xmlns="http://www.w3.org/2000/svg" width="..." height="..." viewBox="0 0 ..."> <path .../> ... </svg>
```

`legend` (alias `key`) adds a colour key; a trailing `#hex` (3- or 6-digit) sets one wedge's
colour. Swap the source and you get a different diagram from the same call. A **flowchart**:

```java
String flow = Sirentide.render("""
    flowchart TD
      A[Open PR] --> B{Approve?}
      B -->|yes| C[Merge]
      B -->|no|  D[Revise]
      D -->|re-review| B
    """);
```

`TD` (top-down, default) or `LR` (left-right); `A[label]` is a rectangle, `A{label}` a diamond;
`-->|text|` labels a single hop; chains like `A --> B --> C` expand; cycles are drawn with a
visible back-edge lane. A **sequence**:

```java
String seq = Sirentide.render("""
    sequence
      Client ->> Gateway : GET /token
      Gateway ->> Auth   : validate
      Auth   -->> Gateway : ok
      Gateway ->> Gateway : sign JWT
      Gateway -->> Client : 200 token
    """);
```

`->>` is a call (solid, filled head), `-->>` a reply (lighter, open head); `A ->> A` is a
self-message; the `: label` after the arrow is optional. Actors register in first-seen order.

Every result is complete and standalone — write it to a `.svg` file, or paste it inline into a
page. A malformed row is skipped and an unknown diagram type degrades to an empty shell — the
bake never fails on one bad line. (See the [gallery](examples/gallery/GALLERY.md) for the diagram
types, `timeline` and `gantt` included, with real-browser renders.)

## 3. From the command line

```bash
echo 'pie
  "A" : 60
  "B" : 40' | java -jar build/libs/sirentide-0.4.0.jar
```

Reads a DSL from stdin, writes the SVG to stdout. *(Planned: `--batch` for many diagrams per
invocation, one JVM.)*

> **Note on math in labels from the CLI.** Math-in-labels needs a `MathFragmentRenderer` injected
> at the API (`Sirentide.render(dsl, mathRenderer)`). The CLI path injects none, so a `$…$` label
> bakes as its **raw LaTeX source text** rather than typeset glyphs — the documented per-fragment
> fail-soft, not an error. To bake real math, call the two-arg API with a renderer.

## 4. Why it's safe to inline

Sirentide emits only inert geometry — filled `<path>`s, no script, no style, no runtime. So the
baked SVG needs **no downstream sanitization** and ships **no JavaScript**. For a static docs
site baking untrusted-author content, that's the whole point.

## 5. Status legend

| Mark | Meaning |
|---|---|
| **built** | Works today: the pipeline, the font-metrics oracle, labels-as-paths, all **twenty-two** diagram types (`pie`, `xychart`, `timeline`, `gantt`, `flowchart`, `sequence`, `state`, `quadrant`, `classDiagram`, `erDiagram`, `gitGraph`, `journey`, `mindmap`, `sankey`, `mathblock`, `matrix`, `heatmap`, `snake`, `tensornetwork`, `young`, `dynkin`, `knot`), semantic anchors (`data-sirentide-role/id/seq`), LatteX-math-in-labels, `alt`/`loop`/`par` sequence frames **and activation bars**, the baked-frame play-through API (`renderFrames`), and the `/docs` ```` ```sirentide ```` fence. |
| *planned* | Designed, not yet built: the native **effect layer** (`data-sirentide-fx`, the security-gated Part 2). |

See [`docs/DESIGN.md`](docs/DESIGN.md) for the full design and [`SLOWSTART.md`](SLOWSTART.md) for
the why.
