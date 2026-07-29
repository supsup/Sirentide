# `renderFrames` packaging for documentation fences

Status: corrected design proposal for plan `71926eda-3d26-4f71-851a-c1b0174577a0`
after Lattice review `PROJECT/sirentide/624`.
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

The in-repository and older published container-contract pages previously
described a future Sirentide runtime and `data-sirentide-fx` as if they were live.
The corrected contract is explicit: neither is admitted today. This proposal
does not depend on either one; its optional control reads only deck structure.

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

The Stafficy renderer adapter must call a new trusted-consumer overload rather
than render at Sirentide's general-purpose limits and inspect the result afterward:

```java
public record FrameBudget(int maxFrames, long maxUtf8Bytes) {}

public static FramesResult renderFramesWithDiagnostics(
    String dsl,
    MathFragmentRenderer math,
    FrameBudget consumerBudget);
```

`FrameBudget` is Java API input from a trusted embedding consumer. It is not a
DSL directive, fence option, CLI flag, or author-controlled value. Both fields
must be positive and no larger than Sirentide's own defense-in-depth limits.
Every existing `renderFrames*` overload keeps its current behavior and byte
identity; compatibility tests compare those overloads against the pre-change
fixtures.

The bounded overload checks the distinct sequence count before emitting any
emphasized frame. It therefore cannot materialize a 33rd frame when passed a
32-frame budget. During emission it computes each completed frame's exact UTF-8
byte length with overflow-safe `long` accounting and checks the prospective
aggregate before retaining that frame. On a consumer-cap breach it discards the
accumulated deck and returns `frames() == List.of()` plus a typed
`Outcome.OUTPUT_CAP_EXCEEDED` diagnostic whose stable detail identifies
`consumer-frame-count` or `consumer-utf8-bytes`. Sirentide's existing 512-frame,
per-frame, and 50 MB aggregate limits remain independent defense in depth.

A successful bounded result emits the existing conformant Sirentide wrapper
with each independently valid SVG as a direct child, in frame order. It emits no
new wrapper class, author-controlled attribute, or inner-SVG vocabulary.

The deck is all-or-nothing. A non-OK diagnostic, null frame, invalid frame, frame
count overflow, or aggregate byte overflow produces the existing visible escaped
diagnostic plus the original fence. A partial deck is never published.

The Stafficy wire path imposes one converter-wide budget across every
`sirentide-frames` fence in a Markdown document: at most 32 retained frames and
4 MiB of exact UTF-8 frame bytes in total. This is deliberately not a per-fence
allowance. It leaves at least 4 MiB of headroom below the 8 MiB caps in both
`DocsChromeBufferingResponseWrapper` and `GateBufferingResponseWrapper` for
ordinary Markdown, wrappers, diagnostics, and page chrome.

The converter starts with the document budget, passes only the remaining count
and bytes into the trusted Sirentide overload for each deck, validates the full
typed result and every frame, and only then atomically appends the deck HTML and
debits the cumulative counters. A deck that would cross the remaining budget
emits the visible escaped diagnostic plus its original fence, consumes none of
the remaining deck budget, and never publishes an incomplete prefix. Previously
completed decks remain valid; no partial SVG from the rejected deck enters the
document.

### Progressive enhancement

An external same-origin `sirentide-frames.js` scans only conformant Sirentide
wrappers containing more than one direct-child SVG. It then:

1. adds runtime-owned classes after sanitization;
2. allocates a stable runtime-owned target id and creates native Previous and
   Next buttons with `aria-controls` pointing to it;
3. creates a `Step n of N` status with `aria-live="polite"`;
4. exposes one frame at a time with the standard `hidden` property; and
5. updates button disabled states and the live status without interpreting DSL,
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
   accessors, add the trusted `FrameBudget` API and its pre-emission/incremental
   cap tests, add asset/runtime/CSP-shape tests, document the opt-in consumer
   contract, and prove ordinary render/frame bytes are unchanged.
2. **Stafficy:** add the exact fence form and typed frame adapter, enforce the
   converter-wide 32-frame / 4-MiB budget, retain sanitizer closure, serve and
   inject the paired assets, and add converter/sanitizer/chrome/end-to-end tests.
3. **stafficy_docs:** publish one live authoring example, capture it through
   BrewShot, and update the authoring and container-contract pages to distinguish
   live behavior from the still-deferred effects layer.

Before either cross-repository edit, the Sirentide umbrella links explicit
child plans: Stafficy integration `27ef2ca1-6120-42ac-b74d-42b7974e60e7` and
published `stafficy_docs` contract correction
`aeee136a-f1ab-4d3d-9da9-11307c31aae5`. Each keeps its own branch and evidence.
Resulting-main verification includes each repository's full test lane, the
Stafficy image rebuild and `/docs` live smoke, a JavaScript-disabled storyboard
check, keyboard control checks, and a BrewShot image of the live deck.

## Decisions recorded from Lattice review 624

1. Use the separate exact `sirentide-frames` fence; do not add a directive to the
   byte-compatible `sirentide` surface.
2. Reuse the exact conformant wrapper with ordered direct-child SVGs and
   runtime-owned post-sanitize classes; grow no sanitizer vocabulary.
3. Keep the paired optional assets in the Sirentide jar and have Stafficy serve
   bytes from that exact dependency, with lineage and CSP-shape tests.
4. Use 32 frames and 4 MiB as the first-cut budget across all frame decks in one
   document, enforced by the trusted consumer API before/while emission.
5. Keep this Sirentide plan as the umbrella and create explicit Stafficy and
   `stafficy_docs` child plans before implementation crosses repository boundaries.
