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
| `data-sirentide-role` | closed enum: `node`, `edge`, `slice`, `actor`, `message`, `bar`, `class`, `point`, `event`, `entity`, `note`, `commit`, `branch`, `task`, `flow`, `cell`, `cluster`, `axis` | what this element *is* |
| `data-sirentide-id` | `^[A-Za-z0-9_-]{1,32}$` | stable id for cross-reference / linking |
| `data-sirentide-seq` | wire (`/docs`): `^[0-9]{1,4}$` · in-process: `^[0-9]{1,9}$` | reading / **play-through** order |

**Role source of truth = `SirentideRole` (the jar-exported enum).** The vendored jar carries the
enum into every consumer, and the Stafficy sanitizer pins its allow-list to
`SirentideRole.WIRE_VALUES` directly — so the enum, not this table, is what enforcement reads.
This table is kept byte-aligned with the enum by a **build-failing drift test**
(`ContractDocDriftTest`) that parses this file and compares. `cluster` and `axis` are
**RESERVED**: admitted by the contract + sanitizer, not yet emitted by any layout.

**The seq split is deliberate.** The in-process contract (`SirentideContract.ANCHOR_SEQ`,
`{1,9}`) stays loose for the unbounded in-process play-through; the `/docs` **wire** bound is
`{1,4}` — the emitter SATURATES the wire value to 4 digits (`SvgEmitter`) precisely so the
sanitizer can enforce the tight documented bound (Lattice-cleared, sirentide #105/#106/#123).

**`data-sirentide-fx` is Part 2 — NOT admitted today.** The emitter never emits it, the
sanitizer strips it, and `SemanticAnchorTest` asserts `isWire("fx")` is false. When Part 2
lands (security-gated, Lattice sign-off), the effect-name enum will ship the same way roles
do — jar-exported and drift-guarded, not doc-as-truth. Planned starting vocabulary (design
intent only): `glow`, `pulse`, `fade`, `draw`, `handscribe`, `spotlight`, `none`.

### Banned on inner elements (build-failing)
Any `on*`; `<script>`; `href`/`xlink:href`; any `style` attribute; any `data-*` outside the closed list above (including `data-sirentide-fx` until Part 2); **any `class` at all on inner elements** (class lives ONLY on the wrapper `<div>`); any executable or navigational affordance.

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
