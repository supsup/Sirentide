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

All **six shipped diagram types** (`pie`, `xychart`, `timeline`, `gantt`, `flowchart`, `sequence`) bake to a strict subset of the contract below — the exact set pinned in `SirentideContract` (`ALLOWED_ELEMENTS` / `ALLOWED_ATTRS`) and asserted by the `ContainmentTest`:

| Element | Attributes emitted today |
| --- | --- |
| `svg` (root) | `xmlns` (= `http://www.w3.org/2000/svg`), `width`, `height`, `viewBox` |
| `path` | `d`, `fill`, `stroke`, `stroke-width` — labels (as glyph paths), pie wedges, flowchart arrowhead triangles; `stroke`/`stroke-width` are the OPTIONAL non-rect node border (`classDef` styling, node-styling milestone) |
| `rect` | `x`, `y`, `width`, `height`, `fill`, `stroke`, `stroke-width` — bars, boxes, node rectangles; `stroke`/`stroke-width` are the OPTIONAL node border set by a flowchart `classDef` (absent → no border, byte-identical to a pre-styling bake) |
| `line` | `x1`, `y1`, `x2`, `y2`, `stroke`, `stroke-width` — axes, lifelines, edges (per-edge colour/width via `linkStyle`) |

**No `<g>`, no `<circle>`/`<ellipse>`/`<polyline>`/`<polygon>`, no `<marker>`/`<defs>`, no `stroke-dasharray`, no `transform`, no `class`, and no `data-*` anchors are emitted yet.** The wider allow-list below is deliberate **contract headroom** (producer ⊆ contract ⊆ sanitizer): the sanitizer preserves it, and the emitter narrows into it — new elements/attributes become *emitted* only when a milestone wires them (per the growth ledger) and `SirentideContract` widens to match.

## The alphabet — GROWS PER MILESTONE

The alphabet starts minimal and grows only as a milestone needs it. **The current emitter (all six diagram types) needs NO `<marker>`/`<defs>`** — arrowheads are emitted as inline `<path>` triangles (pure-path discipline, tiny containment surface). `<marker>`/`<defs>` are added at a later milestone (a fuller `sequence` with activation frames) as a reviewed widening, value-constrained to same-document `#id` refs Sirentide itself emitted.

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
| Shipped — all six types (`pie`, `xychart`, `timeline`, `gantt`, `flowchart`, `sequence`) | the `svg/path/rect/line` set above; arrowheads = inline `<path>`. No new elements were needed — the graph and time-axis types landed on the current path/line surface. |
| Future — fuller `sequence` (activation frames, denser heads) | `<marker>`, `<defs>` — value-constrained: `marker-end`/`marker-start` = `url(#localId)` only, referencing markers Sirentide emitted in the same doc |
| Future — the effect / anchor layer | `<g>` groups carrying the closed `data-sirentide-*` anchor vocabulary (see the container contract) |

Every row is a reviewed doc + test change, not a default.

## Relationship to LatteX
Independent contract, same discipline. When a Sirentide label contains math, the renderer calls `LatteX.render`/`renderInline` and embeds the returned `<g>…</g>` fragment — which is already `svg/g/path/rect`, a **subset** of this alphabet — so the composition adds nothing to Sirentide's surface. (See the Sirentide↔LatteX dependency model, coordination seq 5495.)
