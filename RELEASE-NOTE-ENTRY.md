# Release-note entry — global layout-time work budget (plan fe8c5bbc, slice 2)

> Staged for the `## **0.6.0** — IN PROGRESS` section of `RELEASE_NOTES.md`.
> Written to a separate file per the branch brief; `RELEASE_NOTES.md` is untouched.

---

### A global work budget bounds layout itself, not just its output (plan fe8c5bbc slice 2)

Sirentide's 5 MB output cap could only ever fire once **emit** started — that is, after layout had
already built and was holding the entire scene. A diagram that blew up *during* layout therefore had
no backstop at all: the cap was waiting downstream of the memory it was supposed to protect.

Slice 1 closed the individual blow-up paths that could be named one at a time — per-segment dotted-edge
pieces, sankey column relaxation, timeline label materialization, sequence notes, root-system
projection. A per-path cap bounds **one** path, and says nothing about their sum. A perfectly legal
14 KB flowchart — 500 nodes, 1 000 dotted forward edges, **every segment comfortably under** the
1 000-piece dash cap — still built and held **546 560 shapes**, over 26 MB of live layout state, before
the emitter was ever entered.

That aggregate is now fenced. A single work budget is armed at Sirentide's layout dispatch seam and
charged by **every shape constructed**, so all 23 diagram types and every layout class are covered
without a per-type cap in any of them. Past the budget the bake degrades to the usual inert shell, and
the diagnostics channel reports it the way every other known cap is reported —
`OUTPUT_CAP_EXCEEDED` at stage `layout`, with `MAX_LAYOUT_SHAPE_WORK` named in the detail — never a
`RENDER_BUG`.

**The limit is derived, not chosen.** Each shape is charged a weight that is a strict *lower bound* on
the bytes that shape costs the emitter **if it is retained** (a `<line …/>` costs at least 63 bytes and
is charged 48; a glyph run is charged its exact path length plus 16). A shape that is built and then
discarded charges work and emits nothing — this is a *work* budget, and construction is the work — so
the limit cannot simply borrow the output cap's number. It sits at **double** the cap instead, which
buys a two-sided proof: any scene that fits the 5 MB output cap retains under 5 MB of lower-bound
work, so charging past 10 MB means the bake either **could never have fitted the output cap**, or
**threw away at least an entire cap's worth of construction** — a build-and-discard runaway the size
of the largest legal scene. Today the second arm is unreachable: every shape-construction site in
every layout retains what it builds (a dated census in the budget's class doc), so nothing that
renders today renders differently. But the guarantee no longer rests on that census staying true.
Both legs are measured in the test suite: raising a weight above its true emitted cost fails the
build, and so does moving the charge point off construction.

**Scope and non-scope, stated plainly.** The budget is armed only inside the public API's layout
dispatch, so a direct `FlowchartLayout.layout(...)` call from an embedder or a per-type test is
completely unaffected; a re-entrant `MathFragmentRenderer` callback's nested render gets its own budget
and cannot spend or reset the outer one; and a completed bake leaves no state behind on a pooled render
thread. `CaptionLayout` runs after the dispatch and stays unbudgeted (it is bounded by one wrapped
caption band). Sankey's column-relaxation cap remains and is *not* redundant — relaxation is pure
iteration that produces no shapes, so the shape budget cannot see it. Frame-deck aggregation stays with
`MAX_TOTAL_OUTPUT_BYTES`.

**Two observable changes, both stated.** First: a diagram whose shape work alone already exceeds the
output cap — a 10 000-slice pie, say — now reports its degrade at stage `layout` instead of stage
`emit`. The outcome (`OUTPUT_CAP_EXCEEDED`) and the returned bytes (the inert shell) are identical;
only the stage the abort is attributed to moves. The emit-stage classification is still live and
still pinned for scenes that genuinely reach the emitter before overflowing it. Second, currently
theoretical but documented rather than hidden: a bake that performs more than 10 MB of lower-bound
construction work is aborted **even if its final output would have been small**, because a work
budget bounds building, not keeping. No layout today can produce that band (none discards what it
constructs); the semantics are pinned by a dedicated discriminator test so a future discard-heavy
layout inherits them knowingly instead of silently.
