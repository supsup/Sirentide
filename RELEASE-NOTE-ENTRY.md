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
charged by **every shape constructed**, so all 25 diagram types and all 43 layout classes are covered
without a per-type cap in any of them. Past the budget the bake degrades to the usual inert shell, and
the diagnostics channel reports it the way every other known cap is reported —
`OUTPUT_CAP_EXCEEDED` at stage `layout`, with `MAX_LAYOUT_SHAPE_WORK` named in the detail — never a
`RENDER_BUG`.

**The limit is derived, not chosen.** Each shape is charged a weight that is a strict *lower bound* on
the bytes that shape costs the emitter (a `<line …/>` costs at least 63 bytes and is charged 48; a
glyph run is charged its exact path length plus 16). So a scene's charged work can never exceed the
bytes it would emit — which means the budget can sit exactly at the 5 MB output cap and still be
provably free: **anything it rejects would have blown the output cap anyway** and degraded to the same
inert shell. Nothing that renders today renders differently. The budget only moves an already-doomed
bake's abort earlier, before the gigabytes are retained. The lower-bound property is measured
per shape in the test suite, so raising a weight above its true emitted cost fails the build.

**Scope and non-scope, stated plainly.** The budget is armed only inside the public API's layout
dispatch, so a direct `FlowchartLayout.layout(...)` call from an embedder or a per-type test is
completely unaffected; a re-entrant `MathFragmentRenderer` callback's nested render gets its own budget
and cannot spend or reset the outer one; and a completed bake leaves no state behind on a pooled render
thread. `CaptionLayout` runs after the dispatch and stays unbudgeted (it is bounded by one wrapped
caption band). Sankey's column-relaxation cap remains and is *not* redundant — relaxation is pure
iteration that produces no shapes, so the shape budget cannot see it. Frame-deck aggregation stays with
`MAX_TOTAL_OUTPUT_BYTES`.

**One observable change.** A diagram whose shape work alone already exceeds the output cap — a
10 000-slice pie, say — now reports its degrade at stage `layout` instead of stage `emit`. The outcome
(`OUTPUT_CAP_EXCEEDED`) and the returned bytes (the inert shell) are identical; only the stage the
abort is attributed to moves, and only for scenes that could never have fitted the cap. The emit-stage
classification is still live and still pinned for scenes that genuinely reach the emitter before
overflowing it.
