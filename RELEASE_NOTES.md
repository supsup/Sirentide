# Sirentide — Release Notes

Sirentide turns a small diagram DSL into clean, self-contained **SVG** — pure Java, zero
dependencies, safe to drop straight into a web page, no runtime JavaScript. New to it? See
**[QUICKSTART.md](QUICKSTART.md)** to get going and **[SLOWSTART.md](SLOWSTART.md)** for the why.

---

## 2026-07-30 — Release **0.5.0**

The version moved to 0.5.0 immediately after the 0.4.0 cut so post-release jars could never be
mistaken for the immutable, already-vendored `sirentide-0.4.0.jar`. This cut freezes that accumulated
surface: the `sirentide render <file.md>` parity checker and Docker watch flow, trusted frame-deck
budgets with artifact-paired optional assets, deterministic packing and self-loop layout hardening,
fail-closed display-label diagnostics, and one new diagram type — `rootsystem` — growing the sealed
production inventory **22 → 23**. The detailed contracts and degradation boundaries follow below.

### Source-verifiable immutable artifacts

The executable jar now carries `Sirentide-Source-Revision`, an exact lowercase 40-hex git commit,
beside its implementation version. The release-only
`SIRENTIDE_REQUIRE_CHROME=1 ./gradlew releaseBuild` gate removes old build output, rejects an
unresolvable revision, a dirty worktree, a non-`X.Y.Z` version, unfinished release notes, or a build
where the real-browser pins can skip. It then runs the full build and verifies the executable,
sources, and Javadoc jars; the paired `sirentide-frames.js` / `.css` resources; manifest version and
source identity; and a SHA-256 sidecar for each jar. The tag and GitHub release are therefore bound to
artifacts that can be traced back to one reviewed source commit.

### Each self-loop label rides ITS OWN loop (plan 64cf1bae, reviews sirentide/761 + 768 + 770)

A stacked class/ER self-loop label now tells you which loop it names. Every label sits in ONE
column just past the node's outermost lane leg — clear of every lane line whatever the label's
width — and its BASELINE is aligned with its own loop's top horizontal leg, so it reads as
riding that loop the way an edge label rides its edge. The earlier cut of this work x-staggered
the labels one lane-pitch apart, which separated them but tied none of them to its loop.

Placement is a **contract hierarchy**, not one rule with silent exceptions, and both
degradations are named:

- **Ideal** — the baseline sits on its own loop's top leg, in the constant label column. This is
  what you get whenever nothing else binds.
- **Degradation 1, corridor.** A neighbour edge crossing the label column outranks exact
  leg-association: a label sitting on that edge would read as a label *of* it, which is worse
  than being a few px off its own leg. The move is **per label** (`SelfLoopLabelColumn`), never a
  shift of the whole set: the labels share an x ORIGIN, not an x EXTENT, so a crossing edge can
  run through a wide label's band and miss a narrow one entirely. Only a label whose OWN corridor
  is crossed moves, and only by the minimum that clears it; a label whose corridor is clear — and
  whose leg still fits under the label above — sits EXACTLY on its own leg. Where clearing
  downward is the only escape, the metric floor carries that move to the labels below it: a
  cascade, minimal, and contract too.
- **Degradation 2, metric floor.** Consecutive baselines are separated by the upper label's real
  DESCENT plus the lower label's real ASCENT plus a 2px gap, measured per label (inline math
  included), so the occupied bands are disjoint by construction at any label size. A tall label
  can push the lanes below it off their legs; order is preserved. This replaces a fixed
  one-line-slot budget that ignored what a label actually measured — two tall math fragments
  used to emit overlapping bands while every receipt stayed green.

Both are solved ONCE in the layout pre-pass, from the full label set (floor first, then the
per-label corridor placement over the floored stack — a forward pass in leg order followed by a
backward relax toward the ideals), and canvas reservation and emission consume that single result
— nothing is recomputed at emit. `SelfLoopGeometryTest` pins the ideal, each degradation, and
their COMPOSITION on the stacked fixture (leg association for the unconflicted labels, clearance
for the conflicted ones, the exact cascade minimum, order and pairwise disjointness together);
gallery references `class-self-loop*` and `er-self-loop*` re-captured.

### A dropped thin-slice pie label is NAMED, never silent (plan 86cee1d3)

A pie slice too thin for its outside leader-label used to drop the label with no signal — a
coloured wedge with no name and a clean OK. `renderWithDiagnostics` now reports the drop as an
OK-with-caveat naming the slice and pointing at `pie legend`, which shows every label in the
side key (new gallery twin `pie-thin-labels-legend`). The SVG bytes are unchanged — the caveat
rides alongside, never in the bake. The drop caveat **composes** with the font-coverage caveat
through one carrier (`okDiagnostics` now delegates to `withFontCoverageCaveat`), so at their
intersection both honest notes appear instead of the last-built one shadowing the other
(pinned by a delete-mutant-verified discriminator).

### Tag-shaped display labels now FAIL CLOSED

A label like `A[TRUE NEGATIVE<br/>safe to act on]` used to render `<br/>` as **visible text**
with exit 0, a well-formed SVG, and no diagnostic — every automated check passed and only a
human looking at the picture could tell. Sirentide renders label text literally and has never
supported HTML; the defect was that saying so silently is indistinguishable from succeeding.

Tag-shaped markup in a **display label** is now a parse-level failure: `render` degrades to the
inert shell, `renderWithDiagnostics` reports `PARSE_ERROR` at stage `parse` naming the label's
stable identity and a bounded token, `renderFrames` behaves identically, and
`sirentide render <file.md>` exits **1** and writes nothing — matching what the /docs bake
already does with a fence that will not render.

**Compatibility boundary, deliberately narrow.** Two things stay legal and are pinned by
controls:

- **Ordinary comparison prose.** `x < y`, `a <- b`, `3<5` and `0<x+y>1` all render. The check is
  tag GRAMMAR — a name that terminates at whitespace, `/` or `>` — not "contains a bracket".
- **Inline math on the surface that supports it.** `$…$` in a flowchart node label is still
  rendered through the math renderer and is not scanned as markup.

The math exemption follows **rendering semantics**, not the label text: surfaces that emit plain
glyph paths (config caption, GitGraph labels, mindmap nodes) are scanned in full, because on
those surfaces `$…$` has no math meaning and would otherwise be a delimiter that smuggles markup
past the check.

**Identifiers are unaffected.** `subgraph outer<unsafe> [Outer title]` still sanitizes the id to
`outerunsafe`; only the visible `Outer title` is validated.

### Trusted frame-deck budgets and artifact-paired optional assets
Sirentide now exposes additive trusted-consumer input
`FrameBudget(maxFrames, maxUtf8Bytes)` and a bounded
`renderFramesWithDiagnostics` overload. Both limits must be positive and may only
narrow Sirentide's independent 512-frame / 50-MB producer defenses. The frame-count
gate runs before any emphasized frame is emitted; exact UTF-8 bytes are checked
prospectively before each completed frame is retained. A consumer-cap hit returns
an empty deck plus typed `OUTPUT_CAP_EXCEEDED` diagnostics identifying either
`consumer-frame-count` or `consumer-utf8-bytes`. Existing render and frame overloads
retain their byte-compatible behavior and degrade shapes. The bounded overload also
applies a final invariant fence to every frame-bearing return, including parse or
unsupported diagnostics, producer-cap degrades, and caught fallback frames: a
sufficient budget preserves the original frames and diagnostic, while an insufficient
budget retains no frame.

The same jar now carries optional `sirentide-frames.js` and
`sirentide-frames.css` bytes through defensive `FrameDeckAssets` accessors. The
runtime enhances only conformant wrappers with multiple direct-child SVGs, builds
native Previous/Next controls from fixed text, and uses no HTML sink, inline handler,
author data, or inner-SVG vocabulary. Its stylesheet is inactive until enhancement,
so absent or failed JavaScript leaves every inert frame visible in source order.
Sirentide itself neither injects nor executes these assets; ordinary SVG rendering
remains zero-runtime. The exact `sirentide-frames` fence, document-wide 32-frame /
4-MiB consumer budget, same-origin routes, page injection, CSP proof, and live docs
remain gated Stafficy work. No sanitizer or emitted-SVG contract grows here.

### Deterministic Timeline and GitGraph displayed-label packing
Displayed Timeline event/value labels and GitGraph commit-ID labels now use a
compatibility-gated interval partition over their actual post-clamp emitted
boxes. Existing clean diagrams retain their SVG bytes exactly. When a Timeline
band or GitGraph branch lane would overprint, the earliest-finishing-row rule
allocates the minimum deterministic row count; Timeline shifts/grows its axis
and canvas, while GitGraph carries added label depth into later lane baselines,
spines, and branch/merge connectors. The parser's 10,000-item bound feeds only
linear retained placement state, with no silent display-row cap or overflow
stack. This is deliberately a narrow claim about those displayed labels — it
does not claim every Sirentide label or whole diagram is overlap-free, and it
does not change Flowchart's separate actual-box decollider.

### Docker CLI and watched folders
Sirentide now ships a multi-stage Java 25 Docker build with immutable application jars under
`/opt/sirentide` and a non-root runtime. The original one-shot CLI remains the image's default
entry point (with `cli` as an optional explicit mode), while `watch` adds a long-running folder
flow over `/sirentide/input` and `/sirentide/output`. Complete `.md`, `.markdown`, and
`.sirentide` inputs are atomically claimed into `input/processing`; successful sources move to
`input/finished`, failures move to `input/failed`, and outputs or bounded diagnostics land in
the output mount. Publication never overwrites an existing output or archived source, abandoned
processing claims recover on restart, and concurrent workers converge on one final state even
when a bind-mount driver does not coordinate advisory file locks. Claim IDs and original source
names occupy separate path components, and job-id fallbacks bound derived output and diagnostic
names when appending a suffix would exceed a mounted filesystem's component limit.
Unreadable eligible inputs now receive a bounded failed disposition without copying or exposing
their bytes, and the watcher remains live to process later jobs. Their original inode stays in a
durable `failed/pending` state until diagnostic publication succeeds, so a cleared output-mount
fault is reconciled on restart without overwriting an existing diagnostic or failed archive.
Shared watchers now reconcile a vanished unreadable processing path against the exact
snapshotted inode in pending, collision, and direct failed dispositions. A same-name
unrelated archive cannot certify completion, while losing workers stay live and continue
with later jobs.

### BrewShot gallery coverage ratchet
The real-browser example gallery now photographs the shipped `young` diagram with BrewShot, closing
the one missing reference among Sirentide's 22 production diagram types. A headless drift guard derives
the authoritative type set from the sealed `Diagram` IR hierarchy and requires each type to map to a
declared gallery specimen, parse back to that exact IR class, have a committed non-empty PNG, and appear
in the generated gallery page. Aliases may share their canonical IR representative; a newly shipped type
can no longer leave the README's every-type gallery claim silently false. No production rendering
behavior changed in this audit.

### Bounded layout hot paths
Duplicate semantic-anchor suffix assignment and sequence-note placement now run in linear work, while
Sankey column relaxation has a deterministic 250,000-edge-inspection ceiling. Sequences accept 10,000
notes; the first valid excess note now crosses parsing as a bounded rejection marker and aborts before
caption, title, or theme decoration, yielding the literal inert SVG shell plus a named sequence-note-cap
diagnostic.

### Deterministic finite root-system Coxeter-plane projections
The additive `rootsystem` type renders every root of `Aₙ/Bₙ/Cₙ/Dₙ` through the explicit rendering
cap `n ≤ 24`, plus `E6/E7/E8`, `F4`, or `G2`, as a point in a deterministic Coxeter (Petrie) plane,
with concentric distinct-radius guides and `edges: minimal|none`. Weyl-reflection closure and the
exponent-1 Coxeter eigenspace are computed from the same refactored Dynkin/Cartan authority used by
`dynkin` — no copied coordinate or matrix table, RNG, network, or runtime dependency.
The shared public Dynkin/Cartan catalog now enforces its established inclusive rank-200 Dynkin
boundary before bond/matrix allocation or arithmetic, and uses checked count/Coxeter/label
arithmetic as defense in depth. The more expensive `rootsystem` consumer retains its independent
rank-24 closure/pair-work cap. Its block parser is permissive around prose and malformed type
candidates: the first valid type wins; a recognized invalid `edges:` directive still rejects the
block because that vocabulary is closed.
Rank/root/reflection/pair-work caps keep the bake bounded; an over-dense minimal graph degrades
all-or-none to points/rings with `edges:none`, and the accessible description names the cap rather
than silently drawing a partial graph. Malformed and over-cap types use the universal inert shell.
Guide rings are distinct projected radii, not generically one ring per Coxeter orbit: separate orbits
can coincide radially (A3 has three h=4 orbits but only two radii, with root multiplicities 8 and 4).
The E8 showpiece does satisfy the stronger oracle: eight distinct rings of 30 roots.
Semantic minimal links now use a one-pixel `#8490a1` stroke (3.24:1 non-text contrast against white)
while retaining their `edge` anchors. The complete E8 minimal figure is intentionally static-only:
its 6,720 edge anchors plus 240 point anchors make 6,960 play-through steps, above the shared
512-frame cap, so `render` succeeds while `renderFrames` fails closed to its documented inert frame.
Use `edges:none` or a smaller type when a root-system play-through is required.

### Deep code-audit reconciliation
The repository now carries Marlow's source-level audit of the 2026-07-23 baseline, reconciled against
current main after independent reproduction of all 19 findings. The report distinguishes historical
receipts from present code state, records the focused remediation merges that have already landed,
corrects severity ratings, and keeps the remaining global-work-budget and contract/test gaps explicit.

### Cluster and axis semantic anchors
The final two contract-reserved roles now have producer coverage. Every drawn flowchart subgraph frame
emits one `data-sirentide-role="cluster"` group keyed by its stable subgraph id. Eight primary axis
spines emit `role="axis"`: x/y for xychart, quadrant, and journey, plus the single time axis in timeline
and gantt. All groups share their diagram's existing id sanitizer, collision namespace, and contiguous
emit-order sequence; no SVG element, attribute, or value grammar was widened.

### Flowchart convergent-edge label de-collision (plan ea20153b part 2)
Two labeled edges reaching the **same target** from nearby sources used to place their labels at
nearly the same spot, so their rendered glyph **boxes overprinted in both axes** into an
unreadable mush — the plan's real info-loss case (two transitions into one state had to *drop* a
label to stay legible). The label pass now de-collides on the **actual rendered box** (not the
anchor point): keyed by shared target, when a label's box overlaps one already placed for that
target it is **stacked one line (`EDGE_LABEL_SIZE * 1.5`) below**, greedily fanning further
convergent labels down, and skipping any slot that would drop a label onto a **node box**. Labels
to different targets — or already separated in x or y — are untouched, so every non-colliding
diagram stays **byte-for-byte identical** (all pre-existing flowchart goldens unchanged; one new
`flowchart-convergent` golden pins the stacked output). A **deep** convergent fan (a realistic
sink/error state) can stack far enough to reach the canvas edge, so when the lowest stacked label
would fall past the bottom the canvas **grows in height to contain it + margin** — mirroring the
existing frame/back-edge canvas grows, and firing **only on genuine overflow** so no
non-overflowing diagram's canvas moves (review sir/523; one new `flowchart-convergent-fan` golden
pins the grown output — five labels into one sink, canvas height 168 → 202, every label
in-canvas). This revives the de-collision that was withdrawn on the "x-separated by construction"
argument: that premise was **measured false** (guard `convergentLabelsArePairwiseXDisjoint` at
confluence/flowchart-label-guard @ 277f3f1c — two convergent labels overprinted ~14px in x AND
~5px in y) and **retracted at sirentide/514** (anchor-x separation is not rendered-box-x
separation). The ported guard now passes green, order-independently.

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
gallery capture. The container contract now distinguishes Sirentide's narrow producer output from
Stafficy's broader generic safe-SVG sanitizer, with enum- and prose-backed drift guards.

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
