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

## The alphabet — GROWS PER MILESTONE

The alphabet starts minimal and grows only as a milestone needs it. **M1 (pie / xychart / minimal linear sequence) needs NO `<marker>`/`<defs>`** — sequence arrowheads are emitted as inline `<path>` triangles (pure-path discipline, tiny containment surface). `<marker>`/`<defs>` are added at **M2** (denser diagrams) as a reviewed widening, value-constrained to same-document `#id` refs Sirentide itself emitted.

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

### Banned everywhere (build-failing)
`<script>`, `<style>`, `<foreignObject>`, `<use>`, `<image>`, `<a>`; any `on*` handler; any `href`/`xlink:href`; any `style` attribute; any `url(...)` that is not a same-document `#localId`; any element or attribute not listed above.

### text = paths (the producer/consumer decoupling)
The Stafficy sanitizer *tolerates* `<text>`/`<tspan>` (hand-authored doc SVGs use them), so they appear as "optional, tolerated." But Sirentide's **emitter must not emit them** — it emits measured paths. Producer ⊆ contract ⊆ sanitizer: the emitter's real output is *tighter* than what the sanitizer permits, deliberately.

## Milestone growth ledger
| Milestone | Adds to the alphabet |
| --- | --- |
| M1 (pie/xychart/min-sequence) | the element + attribute sets above; arrowheads = inline `<path>` |
| M2 (fuller sequence, gantt/timeline) | `<marker>`, `<defs>` — value-constrained: `marker-end`/`marker-start` = `url(#localId)` only, referencing markers Sirentide emitted in the same doc |
| M-gate (graph-layout diagrams) | re-decision only; no new alphabet expected (still path/line/polygon) |

Every row is a reviewed doc + test change, not a default.

## Relationship to LatteX
Independent contract, same discipline. When a Sirentide label contains math, the renderer calls `LatteX.render`/`renderInline` and embeds the returned `<g>…</g>` fragment — which is already `svg/g/path/rect`, a **subset** of this alphabet — so the composition adds nothing to Sirentide's surface. (See the Sirentide↔LatteX dependency model, coordination seq 5495.)
