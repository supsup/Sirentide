<!-- Sirentide emitter-output contract (v1, M0). The SVG element + attribute allow-list that
     Sirentide's renderer EMITS and the Stafficy sanitizer PRESERVES — the diagram sibling of
     LatteX's emitter-output-contract. Owner: Confluence (co-owned w/ Fixpoint per Charles's
     directive; Fixpoint's renderer emits to it, Confluence's constrainSirentideWrappers + the
     Stafficy sanitizer enforce it, Lattice security-reviews). Sirentide M0 plan 8372449f. -->

# Sirentide Emitter Output Contract (v1)

Two contracts govern Sirentide on a docs page (mirroring the LatteX pair):

```
   THIS output contract       →  the diagram's SVG BODY  (which elements/attrs the geometry may use)
   sirentide-container-contract → the WRAPPER + inner data-sirentide-* anchors (styling/effects)
```

This one is the **producer→consumer allow-list for the rendered SVG itself**: exactly which SVG elements and attributes Sirentide's renderer may emit, and which the Stafficy `MarkdownHtmlSanitizer` must preserve. It is the diagram analogue of LatteX's emitter-output-contract — but **larger** (diagrams need `stroke`/`line`/`polygon`; LatteX's alphabet is fill-only glyphs), so the discipline matters *more*, not less.

## The invariant (two-way, build-failing)

```
   renderer output   ⊆   THIS CONTRACT   ⊆   MarkdownHtmlSanitizer allow-list
```

- **Producer** (Fixpoint's `sirentide` renderer / CLI) emits **only** the elements + attributes below.
- **Consumer** (the Stafficy sanitizer) preserves exactly this set and **strips everything else**.
- A **left-containment test** on the renderer side scans every emitted SVG and **fails the build** if anything outside this alphabet appears — same mechanism as LatteX's S8 containment test. A mirror e2e test on the Stafficy side asserts a Sirentide SVG survives sanitize byte-for-byte.

The contract is **DERIVED FROM SHARED CONSTANTS**, not free-form strings duplicated per side. Widening it is an explicit, reviewed, per-milestone change to this doc + both tests together.

## Design stance (why this is tighter than mermaid — learned from the mermaid recon)

Mermaid *needs* `<foreignObject>` + injected `<style>` only because it chose HTML labels + CSS-class theming; both are avoidable. Sirentide deliberately does NOT use them:

- **Text = paths.** Labels are rendered as filled `<path>` glyphs (measured at bake time via the owned font-metrics oracle). Determinism (byte-identical cached bakes), no view-time font dependency, no `<text>`-injection surface. `<title>`/`<desc>`/`aria-label` carry the accessible text.
- **No `<style>`.** All presentation is inline attributes; theming rides `currentColor` where possible (dark-mode-native, exactly like LatteX). The Stafficy sanitizer strips `<style>` anyway.
- **No `<foreignObject>`, no `<script>`, no `on*`, no `<a>`/`href`, no external `url()`.**

## What the emitter emits TODAY (the current producer surface)

All **twenty-one shipped diagram types** (`pie`, `xychart`, `timeline`, `gantt`, `flowchart`, `sequence`, `state`, `quadrant`, `classDiagram`, `erDiagram`, `mathblock`, `gitGraph`, `journey`, `mindmap`, `sankey`, `matrix`, `snake`, `tensornetwork`, `young`, `dynkin`, `knot`) bake to a strict subset of the contract below — the exact set pinned in `SirentideContract` (`ALLOWED_ELEMENTS` / `ALLOWED_ATTRS`). The `ContainmentTest` guards that alphabet: it renders a **curated seventeen-type corpus** (`pie` · `xychart` · `timeline` · `gantt` · `matrix` · `flowchart` · `sequence` · `state` · `quadrant` · `classDiagram` · `erDiagram` · `mathblock` · `gitGraph` · `journey` · `mindmap` · `sankey` · `knot`) plus edge cases and fails the build on any element/attribute outside the allow-list — it pins the emitter's *alphabet*, distinct from a full per-type census (the four purely-additive types `snake`/`tensornetwork`/`young`/`dynkin` emit into the same `svg/g/path/rect/line` surface but are not in that corpus). The full allow-list:

| Element | Attributes emitted today |
| --- | --- |
| `svg` (root) | `xmlns` (= `http://www.w3.org/2000/svg`), `width`, `height`, `viewBox`, `role` (fixed value `img`) |
| `g` | `transform` (numeric only), `fill`, and the CLOSED semantic-anchor set `data-sirentide-role` / `data-sirentide-id` / `data-sirentide-seq` — wraps one logical element's shapes (a slice/node/edge/…) and places math fragments on the label baseline |
| `path` | `d`, `fill`, `transform`, `stroke`, `stroke-width` — labels (as glyph paths), pie wedges, flowchart arrowhead triangles, math-fragment glyphs; `stroke`/`stroke-width` are the OPTIONAL non-rect node border (`classDef` styling, node-styling milestone) |
| `rect` | `x`, `y`, `width`, `height`, `fill`, `stroke`, `stroke-width` — bars, boxes, node rectangles; `stroke`/`stroke-width` are the OPTIONAL node border set by a flowchart `classDef` (absent → no border, byte-identical to a pre-styling bake) |
| `line` | `x1`, `y1`, `x2`, `y2`, `stroke`, `stroke-width` — axes, lifelines, edges (per-edge colour/width via `linkStyle`) |
| `title`, `desc` | text-only children of the root `<svg>` (no attributes) — the baked `role="img"` + `<title>` + `<desc>` a11y triple |

The emitter now emits `<g>` (math fragments + the closed `data-sirentide-role`/`-id`/`-seq` anchor set) and `<title>`/`<desc>` alongside the original `svg`/`path`/`rect`/`line` surface. **Still not emitted:** `<circle>`/`<ellipse>`/`<polyline>`/`<polygon>`, `<marker>`/`<defs>`, `stroke-dasharray`, `class`, and the still-gated `data-sirentide-fx` effect anchor. The wider allow-list below is deliberate **contract headroom** (producer ⊆ contract ⊆ sanitizer): the sanitizer preserves it, and the emitter narrows into it — new elements/attributes become *emitted* only when a milestone wires them (per the growth ledger) and `SirentideContract` widens to match.

## The alphabet — GROWS PER MILESTONE

The alphabet starts minimal and grows only as a milestone needs it. **The current emitter (all twenty-one diagram types) needs NO `<marker>`/`<defs>`** — arrowheads are emitted as inline `<path>` triangles (pure-path discipline, tiny containment surface). Activation frames already ship on the current path/line surface; `<marker>`/`<defs>` are the genuinely-deferred part, added at a later milestone (denser `sequence` heads / effect markers) as a reviewed widening, value-constrained to same-document `#id` refs Sirentide itself emitted.

### M1 — allowed elements
`svg`, `g`, `path`, `rect`, `line`, `polyline`, `polygon`, `circle`, `ellipse`.
(Optional, tolerated but not emitted — see note: `text`, `tspan`.)

### M1 — allowed attributes (value-constrained)
| Group | Attributes | Constraint |
| --- | --- | --- |
| geometry | `d`, `x`, `y`, `x1`,`y1`,`x2`,`y2`, `cx`,`cy`, `r`,`rx`,`ry`, `points`, `width`, `height`, `viewBox`, `transform`, `preserveAspectRatio` | numeric / path-data / transform-list only — no `url()`, no expressions |
| presentation | `fill`, `stroke`, `stroke-width`, `stroke-dasharray`, `stroke-linecap`, `stroke-linejoin`, `opacity`, `fill-opacity`, `stroke-opacity` | `currentColor` \| `#hex` \| `none` \| numeric; `stroke-dasharray` = numeric list only |
| identity | `class` | a fixed `sirentide-*` set ONLY (no free-form) — see the container contract |
| a11y | `role`, `aria-label`, `aria-hidden` (on the root `<svg>`); `<title>`, `<desc>` children | text only |
| namespace | `xmlns` (root `<svg>` only) | exactly `http://www.w3.org/2000/svg` — required for a standalone SVG document; same as LatteX's emitter-output-contract (was an attr-table omission, caught by the foundation's ContainmentTest keying on this table — Fixpoint sirentide seq 7) |

### Banned everywhere (build-failing)
`<script>`, `<style>`, `<foreignObject>`, `<use>`, `<image>`, `<a>`; any `on*` handler; any `href`/`xlink:href`; any `style` attribute; any `url(...)` that is not a same-document `#localId`; any element or attribute not listed above.

### text = paths (the producer/consumer decoupling)
The Stafficy sanitizer *tolerates* `<text>`/`<tspan>` (hand-authored doc SVGs use them), so they appear as "optional, tolerated." But Sirentide's **emitter must not emit them** — it emits measured paths. Producer ⊆ contract ⊆ sanitizer: the emitter's real output is *tighter* than what the sanitizer permits, deliberately.

## Milestone growth ledger
| Milestone | Adds to the alphabet |
| --- | --- |
| Shipped — all twenty-one types (`pie` · `xychart` · `timeline` · `gantt` · `flowchart` · `sequence` · `state` · `quadrant` · `classDiagram` · `erDiagram` · `mathblock` · `gitGraph` · `journey` · `mindmap` · `sankey` · `matrix` · `snake` · `tensornetwork` · `young` · `dynkin` · `knot`) | the `svg/path/rect/line` set above; arrowheads = inline `<path>`. No new elements were needed for the geometry — the graph, time-axis, and structured types all landed on the current path/line surface. |
| Shipped — a11y baking | `<title>`, `<desc>` (text-only children of the root `<svg>`) + `role="img"` — the standard deterministic SVG a11y triple |
| Shipped — math-in-labels + the semantic-anchor layer | `<g>` carrying a numeric `transform`, an optional `fill` (math fragments), and the closed `data-sirentide-role`/`-id`/`-seq` anchor vocabulary (see the container contract) |
| Future — fuller `sequence` denser heads / effect markers | `<marker>`, `<defs>` — value-constrained: `marker-end`/`marker-start` = `url(#localId)` only, referencing markers Sirentide emitted in the same doc |
| Future — the effect layer | `data-sirentide-fx` on the `<g>` anchors (the one still-gated anchor — the effect-name enum, security-reviewed before it emits) |

Every row is a reviewed doc + test change, not a default.

## Relationship to LatteX
Independent contract, same discipline. When a Sirentide label contains math, the renderer calls the injected `MathFragmentRenderer`'s `renderFragment` seam (LatteX 0.5.0 supplies the implementation; the core stays zero-runtime-dependency) and embeds the returned `<g>…</g>` fragment — which is already `svg/g/path/rect`, a **subset** of this alphabet — so the composition adds nothing to Sirentide's surface. (See the Sirentide↔LatteX dependency model, coordination seq 5495.)
