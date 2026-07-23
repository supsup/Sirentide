# Sirentide — Release Notes

Sirentide turns a small diagram DSL into clean, self-contained **SVG** — pure Java, zero
dependencies, safe to drop straight into a web page, no runtime JavaScript. New to it? See
**[QUICKSTART.md](QUICKSTART.md)** to get going and **[SLOWSTART.md](SLOWSTART.md)** for the why.

---

## **0.5.0** — IN PROGRESS (unreleased)

The version bumped to 0.5.0 immediately after the 0.4.0 cut, because post-release work changes
public CLI behavior — a jar built from post-0.4.0 main must never be mistakable for the
immutable, already-vendored `sirentide-0.4.0.jar`. In so far: the `sirentide render <file.md>`
render-check verb (plan 6eb098d6 slice A) with bake-parity fence extraction, the 0/1/2
exit-code contract (1 = "/docs would keep this fence verbatim"), and atomic-only `-o` writes —
fail-closed where the filesystem cannot replace atomically, symlink destinations replaced as
path entries (reviews sirentide/471 + 490). Notes finalize at cut time.

---

## 2026-07-22 — Release **0.4.0**

Version bump **0.3.0 → 0.4.0** (to be vendored into stafficy `/docs` as `sirentide-0.4.0.jar`,
part B). One new diagram type — the type count grows **21 → 22** — plus a release-hygiene guard.

### The `heatmap` type (21 → 22)
A continuous-score grid: the comparison matrix's exact frame (grammar, caps, rectangularization,
coordinate-anchored cells, single-backing-rect gridlines) where each cell carries a **0..1
magnitude** — decimal, `NN%`, or `text:value` display override — filled by piecewise-linear
interpolation along a **single-hue sequential blue ramp** (`#eff6ff → #93c5fd → #1e40af`;
sequential-not-rainbow by design, disjoint from the matrix verdict palette). Non-numeric cells
fail closed to the neutral fill; dark-end cells flip their label to white via the shared
contrast rule; a sampled-step **ramp legend** (`scale: "low" --> "high"` names its ends) sits
under the grid as plain rects — **no new SVG element or attribute**, the output-contract
alphabet is unchanged. Fuzz census covers the type with a seed + a hostile-label template
(row label AND scale end); per-cell semantic anchors follow matrix's exact rule, so the FX
layer works unchanged. Reviewed at sir454/455 (adversarial ramp-lerp read, INV-4 legend
containment, artifact-provenance check); landed as `b39581f4` with 732/0/0/0 required-Chrome
plus a CI-scope `gradle build` at the tip.

### Release-hygiene guard
A new `ReleaseDocVersionPinTest` asserts QUICKSTART's build-recipe jar pin equals the gradle
project version, so a cut can no longer ship a stale recipe (the class of drift the 0.3.0
review found by hand across 7 doc sites). `docs/DESIGN.md:72` is deliberately excluded — it
names the *vendored* (stafficy-side) jar and lags by design until each part-B re-vendor lands.

---

## 2026-07-21 — Release **0.3.0**

Version bump **0.2.0 → 0.3.0** (to be vendored into stafficy `/docs` as `sirentide-0.3.0.jar`). The
headline is **five new diagram types** — the type count grows **16 → 21** — plus a semantic **oracle**
for the knot family.
All new types bake to the same minimal, sanitizer-safe `svg` / `g` / `path` / `rect` / `line` alphabet;
no new element or attribute shape reaches the emitter, so each is contained by the same construction as
the existing types.

### Five new diagram types (16 → 21)
- **`snake`** — the continued-fraction / square-snake graph (canonical Çanakçı–Schiffler construction),
  with a dimer/perfect-matching count as its semantic oracle.
- **`tensornetwork`** — Penrose MPS/MPO tensor-network diagrams (cores, bond edges, physical legs).
- **`young`** — Young diagrams (a partition rendered as its row-of-boxes tableau).
- **`dynkin`** — the finite Dynkin diagrams (A/B/C/D/E/F/G Cartan families), degrading malformed /
  unknown / over-cap types to the universal inert shell.
- **`knot`** — knot-projection diagrams (unknot, trefoil, and the figure-eight `4₁`), drawn as
  crossing-gapped closed strands.

### The knot Gauss-code oracle
The `knot` type ships with a geometry-derived **Gauss-code oracle**: it reconstructs the knot's Gauss
code from the *emitted* strand geometry (over/under derived from whether a strand reaches or gaps a
crossing) and asserts it equals the canonical code — a real discriminator for a valid double-point
projection, not a happy-path golden. Six review rounds hardened its path recognizer against the
browser/oracle lexical-divergence class (structure, relative commands, mid-arc closepath, hexadecimal
coordinates, and Java-only whitespace separators) so a mutation a browser renders differently can never
false-green.

### Also
- Flowchart router node-collision avoidance; self-loop marker pitch; empty-node single-band rendering.
- Documentation freshened to match (type counts, emitted-surface contract, the LatteX `0.6.0` math seam).

Measured artifact-to-artifact, `0.2.0 → 0.3.0` is a **type-surface** release: five more diagram types,
each contained by the same minimal-alphabet construction as the existing ones, and a semantic Gauss-code
oracle for the knot family. (A fuzzed geometry-containment trust floor across all types is in review and
will land in a following release.)

---

## 2026-07-17 — Release **0.2.0**

Version bump **0.1.0 → 0.2.0** (commit `829aba0`), vendored into stafficy `/docs` as
`sirentide-0.2.0.jar`. Measured **artifact-to-artifact** against the vendored `0.1.0` jar (which
already carried all fifteen pre-matrix types, `renderFrames`, the M1/M2 flowchart + sequence
surfaces, dark theming, and semantic anchors), notable `0.1.0 → 0.2.0` consumer-visible gains —
**highlights, not a binary-delta census** — include:

- the `matrix` comparison/verdict grid — the 16th type (see below; it degrades to the inert
  `0×0` shell on `0.1.0`),
- the `%% direction:` directive,
- semantic anchors on `matrix`,
- the six per-path OOM caps (their own dated entry below),
- the `%% caption:` band (a captioned diagram grows its canvas for the band; `0.1.0` renders
  the same input uncaptioned at the bare canvas size),
- `classDef` fills actually applied (a `classDef critical fill:#ff0000` node keeps the default
  fill on `0.1.0`),
- `renderFramesWithDiagnostics` overloads on the public `Sirentide` API,
- further behavior fixes recorded in the dated entries below (label wrapping, self-relation
  corrections, …).

**Not in the shipped `0.2.0` jar:** subgraph-id edge routing merged to mainline *after* the
`0.2.0` vendor (jar `11:25`, routing merge `13:39` the same day) and is unreleased until the next
cut — its dated entry **below** describes mainline, not this artifact.

### `matrix` — comparison / verdict grid (16th type)
A `matrix` diagram renders a labelled comparison grid: row/column headers and cells, with a
`text:verdict` cell syntax for a descriptive cell carrying its own colour. Cells bake to the same
`<rect>` + glyph-`<path>` alphabet every other type uses — no new element or attribute shape
reaches the emitter, so it is sanitizer-safe by the same construction as the existing types.
(Sixteenth verified against the parser dispatch at this tip: exactly sixteen diagram kinds,
`matrix` the newest.)

## 2026-07-17 — An edge to a subgraph id routes into the cluster

An edge whose endpoint names a **subgraph** used to mint a separate empty node wearing the
group's name — a phantom. Now it routes into the cluster:

> `flowchart TD`
> `EPR[Scaffold] --> PROJ`
> `subgraph PROJ [Project]`
> `PP[Package] --> QQ[Queue]`
> `end`

the `EPR --> PROJ` arrow points at the cluster's representative member (its first-seen member,
`Package`) instead of drawing a stray "PROJ" box. Routing is symmetric — a subgraph id on the
source side retargets too. An edge to an **empty** subgraph (no members, no representative) drops
whole — loud-or-dropped, never a phantom. A cluster id that is *also* a real, explicitly-declared
node (a `PROJ[Real]` box sharing a subgraph's id) keeps its node and its edges — only a bare
phantom routes. A genuine member edge, and a literal `A --> A` self-loop, are unchanged; a
flowchart with no edge-to-subgraph-id bakes byte-identically.

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
