<!-- Sirentide container contract (v1, M0). The wrapper + the per-element data-sirentide-*
     semantic/FX anchor vocabulary that makes a baked diagram STYLABLE + DIRECTABLE, and how the
     Stafficy sanitizer (constrainSirentideWrappers) enforces it. The diagram sibling of LatteX's
     container-output-contract — but with a deliberate SECURITY FORK: anchors live on INNER
     elements, not just the wrapper. Owner: Confluence (FX layer + constrainSirentideWrappers +
     the runtime). Lattice security-reviews. Sirentide M0 plan 8372449f. -->

# Sirentide Container Contract (v1)

Where the [emitter-output contract](sirentide-output-contract.md) governs the diagram's SVG *geometry*, **this** contract governs the layer that makes a baked diagram **stylable and directable** — the wrapper and the semantic/effect anchors — **without opening an XSS hole.** It is the diagram sibling of LatteX's container-output-contract, and it is where Sirentide's whole thesis (Pillar 2: a native effect/narrative layer) actually lives. I own it end to end: the emitter emits to it, my `constrainSirentideWrappers` enforces it, my runtime reads it.

## The fork from LatteX (read this first — it's the crux)

LatteX's containment invariant is **"the inner `<svg>` is 100% affordance-free."** Every `data-lx-fx-*` lives on the ONE trusted outer wrapper; the effect applies to the whole formula as a unit; nothing inside the SVG carries data-/id-/on- anything. That is build-failing.

**Sirentide breaks that on purpose.** Pillar 2 — "message #3 glows," "the active node pulses," "reveal the flow in reading order" — means effects target **individual elements**, so the anchors MUST live on **inner `<g>` groups**, not only the wrapper. Sirentide therefore cannot inherit LatteX's rule; it needs a **new, weaker-but-still-closed** invariant:

> **The inner SVG may carry ONLY a fixed, closed, value-constrained set of `data-sirentide-*` semantic anchors on `<g>` groups — a semantic skeleton, nothing executable, ever.**

The invariant shifts from *"inner is affordance-free"* to *"inner carries only a closed, typed, value-constrained anchor vocabulary."* Still testable, still build-failing, still a two-way allow-list — just a richer one. Getting this exactly right is the load-bearing security decision of the whole project, which is why it's pinned at M0 (Lattice reviews the "inner elements may carry anchors" loosening).

## The wrapper

Diagrams are block-level (no inline/display split like math):

| | Element | Class (exact, literal) |
| --- | --- | --- |
| a Sirentide diagram | `<div>` | `sirentide sirentide-<type>` (e.g. `sirentide sirentide-pie`) |

`sirentide` is always present; the second class names the diagram type from a closed set (`sirentide-pie`, `sirentide-xychart`, `sirentide-sequence`, …). No other class value on the wrapper.

## The inner-element anchor vocabulary (closed + value-constrained)

Inner `<g>` groups may carry ONLY these attributes, each enum- or pattern-constrained. **Anything else is stripped.**

| Attribute | Allowed value | Meaning |
| --- | --- | --- |
| `data-sirentide-role` | closed enum: `node`, `edge`, `arrow`, `slice`, `bar`, `axis`, `actor`, `message`, `step`, `group`, `label` | what this element *is* |
| `data-sirentide-id` | `^[A-Za-z0-9_-]{1,32}$` | stable id for cross-reference / linking |
| `data-sirentide-seq` | `^[0-9]{1,4}$` | reading / **play-through** order |
| `data-sirentide-fx` | the effect-name enum (shared, drift-guarded — see below) | the effect bound to this element |
| `class` | a fixed `sirentide-*` set only (`sirentide-node`, `sirentide-edge`, …) | never free-form |

**Effect-name enum** (the only accepted `data-sirentide-fx` values) — a **shared, drift-guarded** artifact (the Java enum does not export cross-repo, so THIS DOC is the source of truth). Start with the diagram-appropriate subset of the LatteX vocabulary:
`glow`, `pulse`, `fade`, `draw`, `handscribe`, `spotlight`, `none`.
(Grows as we add effects; adding one updates this enum + the sanitizer allow-list + the runtime together — the enum-drift lesson: a LatteX effect silently vanished from `/docs` this session until the shared contract + sanitizer were re-synced. Do not repeat it.)

### Banned on inner elements (build-failing)
Any `on*`; `<script>`; `href`/`xlink:href`; any `style` attribute; any `data-*` outside the closed list above; any `class` outside the `sirentide-*` set; any executable or navigational affordance.

## `constrainSirentideWrappers` — the Stafficy sanitizer pass (Confluence)

The sibling of `constrainMathWrappers` / `constrainCalloutClasses`. On every Sirentide subtree the sanitizer:
1. Keeps the wrapper `<div class="sirentide sirentide-<type>">` (class value-checked against the closed set).
2. On every inner element, **strips any `data-sirentide-*` not in the allow-list, and any value not matching its enum/pattern** (a rogue `data-sirentide-fx="alert(1)"` → dropped; `data-sirentide-onwhatever` → dropped).
3. Strips any `on*`, `href`, `style`, `<script>`, and any non-`sirentide-*` class.
4. Leaves the geometry (governed by the emitter contract) untouched.

Value-constrain HARD; never free-form. Back it with an **e2e MarkdownHtml survival test**: a Sirentide diagram survives sanitize with its wrapper class + allowed inner `data-sirentide-*` intact, and a crafted rogue attribute is provably stripped — **drift-guarded against the shared enum/pattern constants, build-failing on drift.**

## The FX runtime — mechanism is PLUGGABLE BY TARGET

The recon suggested SMIL / inlined `@keyframes` for "no-runtime" animation. That collides with our primary target: **`/docs` strips both `<style>` and `<script>`**, so inlined keyframes can't survive there, and SMIL carries its own event surface (a Lattice call). So:

- **`/docs` (primary):** the **anchor + one trusted page-level runtime** — the single allowed script, in the trusted head, never inline — reads `data-sirentide-fx` + `data-sirentide-seq` and drives effects + the play-through. This is exactly the LatteX fx-runtime pattern we shipped this session; reuse it.
- **Standalone bake (optional):** for an author who controls their own page, an optional self-contained SMIL / inlined-CSS emit — zero runtime — off the *same* anchors.

Same semantic anchors, two emit backends by trust boundary. Do not spec "SMIL" as THE mechanism; spec the anchor vocabulary and let the backend vary.

## The directable / play-through model (Pillar 2 — the reason to build)

- `data-sirentide-seq` orders the reveal; the runtime steps through it (reveal + highlight in order) — "handscribe for diagrams," a flow you *play*.
- **Autonomous or one-control, never drag.** Play-through fires on scroll-into-view or a single play control — NOT drag, NOT hover-to-scrub (the small-viewport lesson: my wobble/gravwell were invisible until they became autonomous). Diagram effects must be sized to the rendered box and never require interaction to read.
- **reduced-motion honored** (static fallback); **no resting-element mutation** (the transform-box-at-rest bug — set transform state only while animating, clear at rest); **body-overlay** for effects that exceed the element bounds.

## Drift guard (the shared-constants rule)

The role-enum, the effect-name enum, the id/seq patterns, and the `sirentide-*` class set are the **single shared source of truth** (this doc). The emitter pins to it, `constrainSirentideWrappers` pins to it, the runtime pins to it. Any change is one edit here + a matching update to all three, guarded by the e2e survival test — so an effect can never silently die between renderer, sanitizer, and runtime.
