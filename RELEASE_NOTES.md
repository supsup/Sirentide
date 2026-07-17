# Sirentide — Release Notes

Sirentide turns a small diagram DSL into clean, self-contained **SVG** — pure Java, zero
dependencies, safe to drop straight into a web page, no runtime JavaScript. New to it? See
**[QUICKSTART.md](QUICKSTART.md)** to get going and **[SLOWSTART.md](SLOWSTART.md)** for the why.

---

## 2026-07-17 — Robustness: six per-path resource caps (no diagram can OOM the renderer)

A hostile or accidental mega-input can no longer drive the layout into an out-of-memory blowup
before the 5 MB emit cap fires. Each element-multiplying path is now bounded at parse/layout time,
loud-and-visible (the excess drops; a diagram past these bounds is unreadable anyway):

- **Dotted/dashed edges** — `MAX_DASH_PIECES` (1000): a canvas-spanning dotted edge (× up to
  `MAX_EDGES`) no longer strides millions of `<line>`s before the emit cap.
- **Class / ER member rows** — `MAX_DISPLAYED_ROWS` (30) + a synthesized `… (N more)` row: a box
  near the parser's member ceiling stops growing a canvas-blowing tower.
- **Sequence message labels** — `MAX_MSG_LABEL_W` (220): a message across distant actors (a wide
  span) no longer admits a 512-char run; the **math (`$…$`) path** is bounded too — an over-wide
  formula degrades whole to its ellipsized source (it can't be cut mid-run), so a wide composite
  can't render span-independent thousands of px.
- **Matrix columns/cells** — `MAX_COLUMNS` (200): `cols: a,a,…×500k` (or a 500k-cell row) no longer
  forces a cols×rows grid that OOMs before layout.
- **XyChart series** — `MAX_SERIES` (100): each per-row value token is a series; a 500k-token row
  no longer explodes the legend + per-row bars.
- **Embedded math fragments** — `MAX_FRAGMENT_LEN` (64 KiB): a giant composite fragment is bounded
  before it reaches the sanitizer.

Every drop is at the parse/layout boundary, so nothing new reaches the emitter — the sanitizer
surface is unchanged. Each cap carries a mutation-surviving DoS regression; the visual ones are
BrewShot-verified.

## 2026-07-17 — Matrix semantic anchors (the queryable skeleton reaches the last element type)

`matrix` was the only element-bearing diagram with zero semantic anchors. Each data cell now emits
a closed `data-sirentide-role="cell"` + a coordinate-derived id + a row-major `data-sirentide-seq`,
completing the "semantic skeleton, nothing executable" invariant across every type. A hostile cell
label (`<script>…`, an `onerror` img) is pinned to appear **XML-escaped** in the output — the
non-vacuity guard proves the label→escaping-sink path, not merely "no live tag" (which a dropped
label would also satisfy).

## 2026-07-17 — `%% direction:` now steers a flowchart

The config-block directive `%% direction: TD|LR` was parsed but inert — a bare `flowchart`
header ignored it and always laid out top-down. It now drives the layout:

> `%% direction: LR`
> `flowchart`
> `A[Parse] --> B[Layout] --> C[Emit]`

lays out left-to-right, exactly as `flowchart LR` would.

**Precedence — an explicit header token always wins.** `flowchart LR` stays LR and
`flowchart TD` stays TD regardless of any `%% direction:`; the directive is only a *fallback*
for a bare `flowchart`. An unknown value (`%% direction: sideways`) leaves the `TD` default.
The axis-less types (sequence, pie, …) ignore direction and bake **byte-identically** — a
flowchart with no `%% direction:` block is unchanged too.

---

## 2026-07-12 — Node & edge styling (`classDef` stroke/colour + `linkStyle`)

Flowchart nodes and edges can now carry colour, not just a fill.

### `classDef` gains `stroke`, `stroke-width`, and `color`
A class definition already set a node's `fill`; it now also sets its border and its label
colour. Assign it with `class` exactly as before:
> `classDef critical fill:#fee2e2,stroke:#dc2626,stroke-width:2px,color:#7f1d1d`
> `class PayGate,Refund critical`

A node without a class keeps **no border** — every pre-existing bake is byte-identical.

### `linkStyle` — per-edge colour and width
Colour or thicken specific edges by their authoring index (0-based), or every edge with
`default`; an explicit index wins over `default`:
> `linkStyle 0,2 stroke:#dc2626,stroke-width:3px` &nbsp;·&nbsp; `linkStyle default stroke:#94a3b8`

### Safe by construction
Every colour is validated hex-only at the parse boundary (the same guard `fill` uses) and
every width is a bounded finite number (0–40); anything else is **dropped to the default**,
never forwarded. Borders emit into the existing `rect`/`path` alphabet — no new sanitizer
surface, so a styled diagram is as inert on `/docs` as an unstyled one.

## 2026-07-04 — M0 foundation & the first diagram

The project is born and building. This is the M0 foundation plus the first rendering diagram type.

### The render pipeline
The full bake path is in place: **DSL → parse → immutable IR → pure layout (→ coordinates) →
pure emit (→ SVG string)**, over a single shared IR that every diagram type projects into. The
emitter targets a deliberately tiny, sanitizer-safe alphabet and formats numbers deterministically
(byte-identical bakes).
> `Sirentide.render("pie\n \"A\" : 60\n \"B\" : 40")`

### `pie` — the first diagram
A pie chart renders end to end: the own-DSL `pie` form parses to slices, layout turns magnitudes
into angular wedges (pure arithmetic — no graph optimization), and emit serializes each to a
contract-clean `<path>`. A single-slice pie draws a full disc; malformed rows are skipped rather
than failing the bake.
> `pie` &nbsp;·&nbsp; `"Reviews" : 40` &nbsp;·&nbsp; `"Builds" : 30`

### Font-metrics oracle
A clean-room, metrics-only sfnt reader (`head`/`maxp`/`hhea`/`hmtx`/`cmap`) plus a layout-facing
oracle: `advance`, `runWidth` (surrogate-safe), `lineHeight`, and greedy word-wrap → `TextBox`.
Deterministic, no DOM — this is the text measurement label layout needs. The bundled label font
is **STIX Two Math** (OFL), reused from LatteX so labels and (soon) embedded formulas share one
face.

### Foundations
Zero-runtime-dependency Java 25 build (mirrors LatteX), CLI (stdin → stdout), and the founding
[`docs/DESIGN.md`](docs/DESIGN.md) — thesis, the LatteX dependency model, the per-element anchor
security model, and the milestone ladder.

**Planned next:** text labels as paths (glyph-outline reader), `xychart`, a minimal `sequence`
with a play-through, the native effect layer, and the LatteX-math-in-labels composition.

---

## 2026-07-07 — M1: the type explosion, the math moat, and semantic anchors

The DSL grew from one type to **eleven**. Beyond the M0 `pie`, the value/temporal family
(`xychart`, `timeline`, `gantt`), the graph family (`flowchart`, `sequence`, `state`, `quadrant`),
and the structured family (`classDiagram` with all five UML relationship markers, `erDiagram` with
crow-foot cardinalities, `mathblock` standalone display math) all bake end to end.

### The math moat
`$…$` inside **any** label-bearing type is handed to the injected LatteX renderer and baked to real
glyph paths — same layout tree as the SVG, per-label fail-soft (a math failure degrades that one
label, never the bake). Braced/multi-span LaTeX is handled span-aware.

### Semantic anchors + a11y
A closed, typed, value-constrained anchor vocabulary (`data-sirentide-role/id/seq`) is emitted on
inner `<g>` elements across all element-bearing types — the "semantic skeleton, nothing executable"
invariant. Every SVG also carries deterministic `role="img"` + `<title>`/`<desc>` a11y, and a
`renderWithDiagnostics` author-facing side-channel explains any silent label degrade.

### Flowchart clusters
`subgraph … end` titled bounding boxes, with nesting.

## 2026-07-08 — M2: full flowchart fidelity, four more types, theming & play-through

Four more types — `gitGraph` (commit lanes + merges), `journey` (satisfaction map), `mindmap`
(indentation-defined tree), `sankey` (weighted flows in depth columns) — bring the built total to
**fifteen**.

### Full flowchart fidelity
Mermaid node shapes (stadium · circle · hexagon · cylinder · subroutine · rounded) and edge
variants (open link · dotted · thick, each with its own style + arrow IR).

### Theming & config block
A `%% key: value` config block (`title` / `theme` / `direction`) plus theme palettes and a
self-contained background rect, so one bake serves any theme.

### Baked-frame play-through
`renderFrames(seq → N static SVG frames)` — a step-reveal without runtime JavaScript. Tall-fragment
box growth lands multi-row math in labels and roomier class/ER geometry.

### Hardening
A fuzz/invariant pass over all fifteen types pins three universal invariants (every drawn element
stays inside its declared canvas — the visual class the byte-pinned goldens can't see).

## 2026-07-09 — `/docs` integration live

A ```` ```sirentide ```` fenced block in a Stafficy `/docs` page now bakes to a sanitized inline
diagram (vendored jar + converter, mirroring LatteX). BrewShot bumped 0.1.0 → 0.6.0 for crisp
gallery capture; the container-contract drift is closed with an enum-backed guard.

## 2026-07-10 — diagnostics twin for play-through

`renderFramesWithDiagnostics` — a why-did-it-degrade channel for the frame bake, without touching
the never-throw contract of `renderFrames`.

## 2026-07-11 — annotations, semantic colour & label wrapping

### Node-label word-wrap
A `flowchart` node label wider than `MAX_LABEL_W` (180px) now word-wraps to up to three lines and
its box grows to fit, instead of ellipsizing to one line. Every wrapped line is ellipsized to the
same bound (a no-op when it fits) so a spaceless label — a URL, or a single word wider than the
bound — clips cleanly instead of overflowing the box. A single-line label is byte-identical to the
pre-wrap engine.

### Caption / note band
`%% caption: <text>` (alias `%% note:`) renders a centered, word-wrapped annotation band **below any
diagram type** — one post-layout seam, wired into all four render paths. The caption bakes to
`currentColor` glyph `<path>`s (the exact shape every label uses), so it is inert by construction
and needs no sanitizer change. A diagram with no caption is byte-identical to the pre-feature bake.

### Semantic colour classes
`classDef <name> fill:#rrggbb` + `class <id> <name>` colour node box fills on `flowchart` and
`state` — the green=allow / red=deny / amber=decision palette the security diagrams need. Class
fills go through the same `#rrggbb`-only hex gate as a per-node colour, so no new value shape
reaches the emitter; resolution order is per-node `#hex` > class fill > header `nodecolor=` >
default. A per-node colour still wins over its class.

---

## 2026-07-14 — self-relations rendered right (class + ER)

A relation from a thing to itself (`A <|-- A`, `EMPLOYEE ||--o{ EMPLOYEE`) now renders as a
deterministic rectilinear **loop** off the box's right edge — previously the degenerate zero-length
edge drew its marker inside the box, and the interim fix erased the relation entirely. Four review
rounds of geometry hardening (plan `sirentide-correctness-selfrel-caption`, Lattice-reviewed):

### Self-loop lanes that cannot collide
The row cursor reserves each box's full loop **lane** — legs plus the widest measured label — so a
loop label can never escape the viewBox or run through the neighboring box. Multiple self-relations
nest in distinct lanes (each vertical leg one step further out), and the box **grows** so lanes
never clamp together into overpainting collinear legs: every authored relation keeps rendering.
Labels stack one line-slot apart above the loop, clear of the box-center band where a crossing edge
lives. Math-label ascent/descent participate in canvas growth.

### Marker ownership follows the authored operand
A whole/parent kind (`<|--`, markerAtLeft) caps the loop's TOP attach; an arrow kind the BOTTOM —
mirroring both the straight-edge rule and ER's left-cardinality-at-top mapping.

### Oracle receipts
`SelfLoopGeometryTest` bounds the FULL leaf geometry (every glyph/marker path coordinate, not just
line endpoints), rejects any positive-length collinear overlap between edge groups, and pins
pairwise-disjoint label boxes at four lanes. Real-browser gallery captures: `class-self-loop`,
`class-self-loops-stacked`, `class-self-loops-three`, `er-self-loop`.

### Caption single-word overflow
A single word longer than the caption wrap width hard-ellipsizes instead of escaping the canvas.
