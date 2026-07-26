<!-- Sirentide container contract (v1, M0). The live wrapper + per-element
     data-sirentide-role/id/seq vocabulary, the deferred FX vocabulary, and how the Stafficy
     sanitizer (constrainSirentideWrappers) enforces the admitted subset. The diagram sibling of
     LatteX's container-output-contract — but with a deliberate SECURITY FORK: semantic anchors
     live on INNER elements, not just the wrapper. Lattice security-reviews. Sirentide M0 plan
     8372449f. -->

# Sirentide Container Contract (v1)

Where the [emitter-output contract](sirentide-output-contract.md) governs the diagram's SVG
*geometry*, **this** contract records the admitted wrapper and semantic-anchor layer, plus the
security boundary a future effect/narrative layer must cross. It is the diagram sibling of
LatteX's container-output contract. The wrapper and `role`/`id`/`seq` anchors are live; the FX
vocabulary and page runtime are deferred. **There is no Sirentide page runtime in `/docs` today.**

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
(`ContractDocDriftTest`) that parses this file and compares. `cluster` is emitted on each
flowchart subgraph frame (id from the stable subgraph id); `axis` is emitted on the eight primary
axis spines across xychart, quadrant, journey, timeline, and gantt. These roles use the same closed
id/seq value constraints as every other semantic group.

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

## Deferred FX runtime — mechanism remains pluggable by target

The following is future design context, not a description of shipped behavior.
`/docs` strips both `<style>` and `<script>`, so inline keyframes cannot survive
there, and SMIL carries its own event surface (a Lattice call). If Part 2 is
approved and implemented, target-specific mechanisms may include:

- **`/docs` (proposed):** one trusted external page-level runtime, never inline,
  could read a future admitted `data-sirentide-fx` plus the existing
  `data-sirentide-seq` anchor and drive effects or play-through.
- **Standalone bake (optional, proposed):** an author-controlled page could opt
  into a self-contained SMIL or inline-CSS emitter off the same anchors.

Any implementation requires a separate Lattice security gate, an enum-backed
effect vocabulary, sanitizer admission, CSP verification, and runtime tests.
Do not specify SMIL as the universal mechanism; trust boundaries differ.

## Deferred directable / play-through model (Pillar 2)

If this layer is later admitted:

- `data-sirentide-seq` may order reveal and highlight steps.
- Use autonomous or one-control playback, never drag or hover-to-scrub; the
  static diagram must remain readable without interaction.
- Honor reduced motion with a static fallback, avoid resting-element mutation,
  and use a body overlay for effects that exceed element bounds.

## Drift guard (the shared-constants rule)

The jar-exported `SirentideRole`, `SirentideContract` patterns, and the emitted
`sirentide-*` class set are the live source of truth. This document mirrors that
surface, and `ContractDocDriftTest` fails the build when its role/id/seq claims
drift. No effect-name enum or Sirentide page runtime exists today. If Part 2 is
admitted, its enum, sanitizer rules, emitter behavior, and runtime tests must be
added together before this document may describe them as live.
