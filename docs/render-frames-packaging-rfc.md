# `renderFrames` packaging for documentation fences

Status: design proposal for plan `71926eda-3d26-4f71-851a-c1b0174577a0`.
This document does not widen the emitted SVG or Stafficy sanitizer contracts.

## Current source of truth

The following current-main behavior bounds this proposal:

- `Sirentide.renderFramesWithDiagnostics` lays out once and returns deterministic,
  independently inert SVG frames. It fails atomically to one inert frame, caps the
  renderer at 512 frames and 50 MB aggregate output, and never needs script, style,
  animation, or navigation inside an SVG.
- Stafficy's `SirentideDiagramConverter` recognizes only the exact `sirentide`
  fence and asks its renderer for one SVG. It emits the exact existing wrapper,
  `div.sirentide.sirentide-<type>`.
- Stafficy's sanitizer admits that exact wrapper and only the closed
  `data-sirentide-role`, `data-sirentide-id`, and `data-sirentide-seq`
  vocabulary on descendant `g` elements. There is no Sirentide page runtime.
- The `/docs` chrome already supports optional same-origin external JavaScript and
  CSS under its CSP, as demonstrated by the LatteX runtime. Inline author script,
  inline author style, event attributes, links inside SVG, and new free-form data
  attributes remain out of bounds.

An older published container-contract page describes a future Sirentide runtime
and `data-sirentide-fx` as if they were live. Current code and the in-repository
contract are authoritative: neither is admitted today. This proposal does not
depend on either one.

## Goal and non-goals

The goal is an opt-in documentation fence whose output is a small deck of static,
CSP-clean SVG frames that a trusted page control can flip through. Existing
`sirentide` fences must remain byte- and behavior-compatible.

This slice does not add autoplay, author-provided runtime options, inner-SVG
attributes, animation primitives, sanitizer vocabulary, or a general effects
layer. It does not change `render`, `renderFrames`, or any emitted frame bytes.

## Proposed contract

### Author surface

Reserve the exact top-level info string `sirentide-frames` for an opt-in deck:

````text
```sirentide-frames
flowchart LR
  Draft --> Review --> Ship
```
````

The existing exact `sirentide` info string continues to render one static SVG.
Unknown and nested fences continue to pass through verbatim.

### Bake surface

The Stafficy renderer adapter calls `renderFramesWithDiagnostics` through a typed
frame outcome. A successful deck emits the existing conformant Sirentide wrapper
with each independently valid SVG as a direct child, in frame order. It emits no
new wrapper class, author-controlled attribute, or inner-SVG vocabulary.

The deck is all-or-nothing. A non-OK diagnostic, null frame, invalid frame, frame
count overflow, or aggregate byte overflow produces the existing visible escaped
diagnostic plus the original fence. A partial deck is never published.

The Stafficy wire path should impose a tighter budget than the general-purpose
in-process API. The proposed first-cut limits are 32 frames and 4 MiB of UTF-8
frame bytes per fence. Both are checked before the HTML fragment is joined. The
renderer's 512-frame and 50-MB bounds remain defense in depth, not the `/docs`
budget.

### Progressive enhancement

An external same-origin `sirentide-frames.js` scans only conformant Sirentide
wrappers containing more than one direct-child SVG. It then:

1. adds runtime-owned classes after sanitization;
2. creates Previous and Next buttons plus a `Step n of N` status;
3. exposes one frame at a time with the standard `hidden` property; and
4. updates button disabled states and the live status without interpreting DSL,
   labels, ids, or arbitrary attributes.

The companion stylesheet is inactive until the runtime adds its ready class. If
JavaScript is blocked, absent, or fails, every inert frame remains visible in
source order as an honest static storyboard. The first slice has no autoplay or
transition, so reduced-motion users receive the same discrete step control.

The runtime and stylesheet should be bundled in the Sirentide jar and exposed by
public resource accessors, matching the proven LatteX asset-lineage pattern. A
consumer serves the assets from the same jar it renders with; the runtime cannot
drift from the deck contract. The Stafficy child slice owns same-origin routes,
docs-chrome injection, and live CSP verification.

## Security and accessibility invariants

- No sanitizer allow-list growth is required.
- Every frame must independently pass the existing SVG and Sirentide-wrapper
  sanitizer constraints.
- The runtime creates controls with DOM methods and fixed text; it never assigns
  `innerHTML` or evaluates content-derived code.
- The unenhanced document remains readable and complete.
- Enhanced controls are keyboard-native buttons, name their target, expose the
  current step, and never make the SVG's existing title/description inaccessible.
- A malformed or oversized deck degrades visibly and atomically to author source.

## Repository split and verification

The proposed delivery split is:

1. **Sirentide:** bundle the optional runtime and stylesheet, expose resource
   accessors, add asset/runtime/CSP-shape tests, document the opt-in consumer
   contract, and prove ordinary render/frame bytes are unchanged.
2. **Stafficy:** add the exact fence form and typed frame adapter, enforce the
   tighter wire budgets, retain sanitizer closure, serve and inject the paired
   assets, and add converter/sanitizer/chrome/end-to-end tests.
3. **stafficy_docs:** publish one live authoring example, capture it through
   BrewShot, and update the authoring and container-contract pages to distinguish
   live behavior from the still-deferred effects layer.

Resulting-main verification includes each repository's full test lane, the
Stafficy image rebuild and `/docs` live smoke, a JavaScript-disabled storyboard
check, keyboard control checks, and a BrewShot image of the live deck.

## Decisions requested from Lattice

1. Is a separate exact `sirentide-frames` fence preferable to a directive inside
   the existing fence?
2. May the first implementation reuse the exact current wrapper with multiple
   direct-child SVGs and runtime-only classes, preserving zero sanitizer growth?
3. Should the optional assets live in the Sirentide jar, with Stafficy serving the
   exact vendored resources, or should Stafficy own those assets?
4. Are 32 frames and 4 MiB per fence acceptable first-cut `/docs` budgets?
5. Should the current Sirentide plan remain the parent with separate Stafficy and
   stafficy_docs child plans before implementation crosses repository boundaries?
