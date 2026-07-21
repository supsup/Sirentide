# Sirentide — design

*Founding design doc. Consolidates the RFC + the review round (Fixpoint's 4-agent audit; Confluence's deep review; Lattice's security review). Owner: Fixpoint (core) · FX/contracts/sanitizer: Confluence · Security: Lattice · Human lead: Charles.*

## 1. Thesis

**Living, *narratable* baked diagrams.** The native effect/narrative layer is the product; the renderer serves it — the same way LatteX's SVG exists to carry its `\lx` effects. Mermaid is a **design reference** (read for geometry ideas, borrow the good, skip the rot), *not* a rival: we are not tied to its syntax and deliberately do things it can't.

Pure-Java-25, zero runtime dependency, clean-room. Diagrams bake to **static SVG at build time** — inert geometry, no runtime JS, survives a strict HTML sanitizer untouched.

## 2. Why build this (and not `mermaid-cli`)

`mmdc` (mermaid in headless Chromium at bake) *can* emit static SVG — so "no runtime JS" is not the differentiator. It's rejected on two grounds Charles ruled decisive:

1. **Hermetic build.** `mmdc` drags a ~300 MB headless-Chromium/Puppeteer dependency into the bake path — non-hermetic, drift-prone. Sirentide stays pure-JVM zero-dep.
2. **Native effect layer.** Effects can only be bolted onto `mmdc`'s output by post-processing a class-soup that drifts every mermaid release. Sirentide emits SVG with its own stable semantic anchors, so effects will be native, stable, ours — the anchors ship today; the effect layer that rides them is the planned Part 2 (§7).

Honest tradeoff, accepted: more layout work, and we trail mermaid on breadth (esp. auto-layout flowcharts — §7).

## 3. The genuinely-new capabilities (the reason to build)

Mermaid has ~zero native animation (a single CSS marching-ants dash loop); all its interactivity is post-render browser JS, never baked. A baked narrative layer is a category it never entered.

- **★ LatteX-math IN diagram labels** *(lead feature — **SHIPPED**)* — a node/edge/message/axis label that IS a real baked LaTeX formula (incl. braced `\frac{a}{b}`/`\sqrt{x}`, and standalone `mathblock`). Sirentide calls LatteX's `renderFragment` at bake; the two minimal-alphabet emitters compose for free. Mermaid's math is runtime KaTeX — impossible in a CSP-clean static bake.
- **Directable / playable flows** *(planned — the thesis frontier)* — step through a flow; reveals in reading order; the active step pulses. A process as a narrated slideshow, not a snapshot. Rides on the semantic anchors — emitted across the element-bearing types today, with anchoring the few remaining types tracked in the growth ledger (§7).
- **Effects bound to meaning** *(planned — `data-sirentide-fx`, the one still-gated anchor)* — "glow the critical path", "flash the error edge" — keyed off semantics we emit as stable anchors.
- **Publication-grade typography** — LatteX-grade font-metrics discipline on labels.

## 4. Architecture

- **Separate project; thin one-way dependency** Sirentide → LatteX. Pinned immutable `com.lattex:lattex:0.6.0`; API surface = `render` / `renderInline` / **`renderFragment`** — the embedded-math API that shipped for math-in-labels (returns `{innerSvg, widthPx, heightPx, depthPx}`, left-end-of-baseline origin, FragmentGuard-clean; no internals). Confluence owns LatteX's public API. Contract, not coupling. **Hermetic:** in Sirentide's core the LatteX dep is *test-scope only* — a consumer injects a `MathFragmentRenderer`, so the core stays zero-runtime-dependency.
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

## 7. Status & milestones

**Twenty-one diagram types ship today**, all on the minimal `svg/g/path/rect/line` + text-as-paths alphabet:
`pie` · `xychart` · `timeline` · `gantt` · `flowchart` (layered `TD`/`LR`; **all mermaid node shapes** — rect/rounded/stadium/circle/hexagon/cylinder/subroutine/diamond; **all edge types** — solid/open/dotted/thick with/without arrowheads; per-hop edge labels, chained/cyclic edges, **subgraph/cluster** containers) · `sequence` (calls/replies/self + **activation bars** + **alt/loop/par** frames + **notes & participant create/destroy**) · `state` · `quadrant` · `classDiagram` (UML — name/attribute/method compartments + the five relationship markers: inheritance/composition/aggregation/association/dependency) · `erDiagram` (entity tables + crow-foot cardinality) · `mathblock` (standalone full-size display math) · `gitGraph` (commit lanes + branch/merge, per-branch colours) · `journey` (user-journey satisfaction map) · `mindmap` (indentation-defined tree) · `sankey` (weighted flow bands) · `matrix` (comparison / verdict grid — rows × columns × closed-vocabulary colour cells) · `snake` (continued-fraction snake graph — the canonical Çanakçı–Schiffler square snake, `cf:` partial quotients → tiles) · `tensornetwork` (Penrose graphical notation — an `mps`/`mpo` chain of tensor cores, bond edges + dangling physical legs) · `young` (integer-partition Young diagram — left-justified rows of unit boxes, English convention) · `dynkin` (semisimple Lie-algebra classification — the finite A/B/C/D/E/F/G families by rank) · `knot` (classical knots — the built-in trefoil/unknot/figure-eight (4₁), guarded by a Gauss-code containment oracle).

**The moat is live.** ★ **LatteX-math in labels** shipped end to end: real baked LaTeX renders in **every** label-bearing type (node / edge / message / state / quadrant / …), including **braced** constructs (`\frac{a}{b}`, `\sqrt{x}`, `x^{2n}`) and standalone display math (`mathblock`). Sirentide calls **LatteX 0.6.0's `renderFragment`** at bake through a hermetic seam — the core keeps its **zero runtime dependency** (LatteX is a test-scope demonstrator; a consumer injects the `MathFragmentRenderer`). Mermaid's runtime-KaTeX is impossible in a CSP-clean static bake.

**The emitter now emits `<g>`** — for **accessibility** (`<title>`/`<desc>`/`role="img"`, deterministic, baked from the IR), for placed **math fragments**, and for **semantic anchors** (`data-sirentide-role`/`-id`/`-seq`, the §5 vocabulary — Lattice-approved, emitted across the diagram types: node/edge/actor/message/slice/bar/point/task/class/entity/event/commit/branch/flow/note/cell; `cluster`/`axis` are contract-reserved, not yet emitted). The whitelist id-sanitizer (§5) is built. Also shipped: `renderWithDiagnostics` — a why-is-my-diagram-empty channel that classifies parse/layout/emit/cap failures while keeping the inert-shell bake byte-identical — and its frames twin `renderFramesWithDiagnostics`, the same classified channel for the play-through bake (frames stay byte-identical to `renderFrames`).

**The Stafficy `/docs` integration has landed.** A ```` ```sirentide ```` fence in a docs page bakes to a sanitized inline diagram end to end: the vendored `sirentide-0.3.0.jar` + the sync-time `SirentideDiagramConverter` pass (mirroring LatteX's, plan sirentide-docs-integration) render the fence body BEFORE flexmark and flow it through the same `MarkdownHtmlSanitizer` chokepoint — so the anchors and the whole renderer ARE now reachable via `/docs`.

**Remaining — the thesis "spin" layer:** play-through / step-reveal; effects bound to meaning (`data-sirentide-fx`, the one still-gated anchor); semantic anchors on the remaining not-yet-emitting types; `marker`/`defs` (value-constrained, same-doc `#id` only) if/when an effect needs them. Full graph auto-layout — crossing-minimization (NP-hard), **ELK/dagre-class** — is deliberately **not** attempted; an explicit re-decision, never a default (current grid/layered layouts are v1, with a layout-quality pass queued).

*(Historical milestone ladder M0–M-gate is preserved in git history; the renderer outran it — every diagram-geometry M-level through M-gate is built, and M2's full `sequence` (alt/loop/par frames + activation bars) shipped. The effect layer the M1 demonstrator was to carry (`data-sirentide-fx`) is the one still-planned piece.)*

## 8. Ownership

- **Fixpoint** — core: IR, parser, layout, emit, font-metrics, DSL.
- **Confluence** — `sirentide-output-contract` + `constrainSirentideWrappers` + FX/effects layer + play-through runtime + LatteX API extension.
- **Lattice** — security review: the inner-anchor loosening, the emitter contract, `marker`/`defs` URL-scrub at M2.

## 9. Non-goals

Feature parity with mermaid. Auto-layout flowcharts before M-gate. A shared `lattex-core` extraction now (Conway: don't ossify a guessed boundary — revisit post-M1 only if the fx-runtime becomes substantial shared *code*).
