# Sirentide — design

*Founding design doc. Consolidates the RFC + the review round (Fixpoint's 4-agent audit; Confluence's deep review; Lattice's security review). Owner: Fixpoint (core) · FX/contracts/sanitizer: Confluence · Security: Lattice · Human lead: Charles.*

## 1. Thesis

**Living, *narratable* baked diagrams.** The native effect/narrative layer is the product; the renderer serves it — the same way LatteX's SVG exists to carry its `\lx` effects. Mermaid is a **design reference** (read for geometry ideas, borrow the good, skip the rot), *not* a rival: we are not tied to its syntax and deliberately do things it can't.

Pure-Java-25, zero runtime dependency, clean-room. Diagrams bake to **static SVG at build time** — inert geometry, no runtime JS, survives a strict HTML sanitizer untouched.

## 2. Why build this (and not `mermaid-cli`)

`mmdc` (mermaid in headless Chromium at bake) *can* emit static SVG — so "no runtime JS" is not the differentiator. It's rejected on two grounds Charles ruled decisive:

1. **Hermetic build.** `mmdc` drags a ~300 MB headless-Chromium/Puppeteer dependency into the bake path — non-hermetic, drift-prone. Sirentide stays pure-JVM zero-dep.
2. **Native effect layer.** Effects can only be bolted onto `mmdc`'s output by post-processing a class-soup that drifts every mermaid release. Sirentide emits SVG with its own stable semantic anchors, so effects are native, stable, ours.

Honest tradeoff, accepted: more layout work, and we trail mermaid on breadth (esp. auto-layout flowcharts — §7).

## 3. The genuinely-new capabilities (the reason to build)

Mermaid has ~zero native animation (a single CSS marching-ants dash loop); all its interactivity is post-render browser JS, never baked. A baked narrative layer is a category it never entered.

- **★ LatteX-math IN diagram labels** *(lead feature)* — a node/axis/bar label that IS a real baked LaTeX formula. Sirentide calls LatteX at bake; the two minimal-alphabet emitters compose for free. Mermaid's math is runtime KaTeX — impossible in a CSP-clean static bake.
- **Directable / playable flows** — step through a flow; reveals in reading order; the active step pulses. A process as a narrated slideshow, not a snapshot.
- **Effects bound to meaning** — "glow the critical path", "flash the error edge" — keyed off semantics we emit as stable anchors.
- **Publication-grade typography** — LatteX-grade font-metrics discipline on labels.

## 4. Architecture

- **Separate project; thin one-way dependency** Sirentide → LatteX. Pinned immutable `com.lattex:lattex:0.1.0`; API surface = `render` / `renderInline` only (no internals). Likely first API ask: `renderMeasured({svg,w,h,baseline})` — Confluence owns extending LatteX's public API. Contract, not coupling.
- **Separate pure LAYOUT (→ coordinates) from pure EMIT (coordinates → SVG string)**, over a single typed **immutable IR** all diagram types project into (the shared IR mermaid never built). Own **font-metrics oracle** — no DOM, deterministic, byte-identical caches.
- **Two contracts** (Confluence authors; drift-guarded shared source): the emitter alphabet (`sirentide-output-contract`) and the per-element anchor security model (§5).

## 5. Security model — the load-bearing fork from LatteX

LatteX's invariant: the inner SVG is **100% affordance-free** (every effect hook lives on the ONE outer wrapper). Sirentide's per-element FX ("node 3 pulses", "edge msg-2 glows") **breaks that on purpose** — anchors must live on inner `<g>` elements. So Sirentide needs a **new, weaker-but-still-closed** invariant:

> The inner SVG may carry ONLY a fixed, closed, value-constrained set of `data-sirentide-*` semantic anchors on `<g>` groups — nothing executable, ever.

- `data-sirentide-role` ∈ a closed enum {node, edge, slice, bar, actor, message, step, group, label, …}
- `data-sirentide-id`  = `^[A-Za-z0-9_-]{1,32}$` — deterministic, page-local (no cross-diagram collisions)
- `data-sirentide-seq` = `^[0-9]{1,4}$` (reading / play order)
- `data-sirentide-fx`  = the shared, drift-guarded effect-name enum
- `class` = a fixed `sirentide-*` set only (no free-form)
- **Banned, build-failing:** any `on*`, `<script>`, `href`/`xlink:href`, `style` attr, `<foreignObject>`, external `url()`, any `data-*` outside the closed list.

The invariant shifts from *"inner is affordance-free"* to *"inner carries only a closed, typed, value-constrained anchor vocabulary — a semantic skeleton, nothing executable."* Still testable, still build-failing, still a two-way allow-list. `constrainSirentideWrappers` (Confluence) enforces it Stafficy-side; the emitter honors it; the left-containment test guards both. **Lattice blesses the inner-anchor loosening.**

### FX mechanism = pluggable by target
`/docs` strips both `<script>` and `<style>`, so inlined `@keyframes` can't survive and SMIL carries its own event surface. Primary target = **anchors + one trusted page-level runtime** (reuse LatteX's fx-runtime pattern). Optional standalone-bake backend (SMIL / inlined `@keyframes`) for authors on their own CSP. Same anchors, two emit backends.

## 6. Contract discipline & battle-scars (from the LatteX build)

- **Contract-first:** producer ⊆ contract ⊆ consumer, with a **build-failing drift test**. The single discipline that merged the LatteX↔Stafficy seam clean. Applies to *both* Sirentide contracts.
- **The effect enum is a shared, drift-guarded artifact** — or effects silently die downstream (this happened to LatteX this session). The contract doc is the source of truth; emitter and sanitizer both pin to it.
- **Effects must read in a constrained viewport** — autonomous-on-scroll or one-button, sized to fit the box, never require drag, never overflow.
- **Never mutate a resting element** (transform state only while animating; clear at rest).
- **reduced-motion, body-overlay, runtime-is-the-only-JS** — transfer verbatim from LatteX's fx-runtime.
- **Re-implement, don't port** — read mermaid for geometry ideas; rebuild clean on our IR.
- **text = paths** — deterministic, byte-identical caches, no view-time font dependency; `<title>`/`<desc>`/`aria-label` carry the a11y text.

## 7. Milestones

The renderer moved faster than the ladder first drew it: the graph and time-axis types (`flowchart`, `gantt`, `timeline`) landed on the current path/line alphabet, so they are **built now** rather than deferred to M3/M-gate. Six diagram types ship today. What remains is the *thesis* layer (play-through, effects, math) and the denser `sequence` machinery.

- **M0 — built.** The two contracts + the label font (**STIX Two Math**, OFL, reused from LatteX — `resources/com/sirentide/font/`, SHA `49b22767…`; same face LatteX renders formulas in, so prose labels + embedded math are one coherent family; build-time-only via text=paths) + the font-metrics *oracle* (sfnt parse + text-run/wrap/multi-line — new work vs LatteX's single-glyph advances) + theming-without-`<style>` (inline fills, `currentColor` for light/dark) + bake-time error handling (malformed → inert, never raw HTML) + own-DSL decision + core scaffold (IR, layout/emit split, font-metrics oracle). Emitter alphabet is minimal and matches the output contract: `svg/path/rect/line` + text-as-paths + geometry + numeric `fill/stroke/stroke-width`; **no `g`, no `marker`/`defs` yet** (arrowheads are inline `<path>` triangles). The LatteX thin dep and math-in-labels composition remain *planned* — the emitter and contract are ready for it, but it is not yet wired.
- **M1 — built (renderer); GO/NO-GO on the *effect* still open.** `pie` + `xychart` (arithmetic layout) **plus a minimal linear sequence** (grid arithmetic — `->>` calls, `-->>` replies, self-messages; no alt/loop/par blocks). The linear-sequence demonstrator ships; the **play-through** and semantic-anchor layer that ride it — the thing that proves the *spin* — are *planned*. A math-in-a-label demo is *planned* (pending the LatteX wire-in).
- **M2 — planned.** Full sequence (alt/loop/par, activations) at its real size; `marker`/`defs` added value-constrained (same-doc `#id` refs only).
- **M3 — built.** `gantt` + `timeline` (proportional time axis; ISO dates shown as dates; degenerate domains still draw markers).
- **M-gate — built (bounded).** `flowchart` (`TD`/`LR`, rect/diamond nodes, per-hop edge labels, chained edges, cycle-tolerant with visible back-edge lanes) on a **layered, deterministic** layout. Full graph auto-layout — crossing-minimization (NP-hard), **ELK/dagre-class** — is deliberately *not* attempted; if ever pursued it stays an explicit re-decision, not a default.

## 8. Ownership

- **Fixpoint** — core: IR, parser, layout, emit, font-metrics, DSL.
- **Confluence** — `sirentide-output-contract` + `constrainSirentideWrappers` + FX/effects layer + play-through runtime + LatteX API extension.
- **Lattice** — security review: the inner-anchor loosening, the emitter contract, `marker`/`defs` URL-scrub at M2.

## 9. Non-goals

Feature parity with mermaid. Auto-layout flowcharts before M-gate. A shared `lattex-core` extraction now (Conway: don't ossify a guessed boundary — revisit post-M1 only if the fx-runtime becomes substantial shared *code*).
