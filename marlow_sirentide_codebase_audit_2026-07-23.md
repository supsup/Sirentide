# Sirentide Deep Code Audit

- **Auditor:** Marlow
- **Date:** 2026-07-23
- **Baseline:** `e9780963ee7071b5b8b7d60cdde03895ed5d2224` (`Merge fixpoint/render-check-cli`)
- **Audit branch:** `marlow/sirentide-deep-code-audit-2026-07-23`
- **Scope:** Core API, parser, immutable IR claims, layout engines, SVG emission and containment, accessibility descriptions, CLI boundaries, font handling, tests, performance, duplication, and maintainability

**Repository changes:** This report only. No production source, GitHub issue, pull request, or remote branch was changed.

## Executive assessment

Sirentide has a strong foundation: it is deterministic, zero-runtime-dependency, built around typed IR and a small SVG alphabet, and unusually well covered by regression tests. The full headless suite passed at the audited commit: **792 tests in 78 suites, 0 failures, 0 errors, 0 skips**. The architecture also contains real defense-in-depth: UTF-8 input accounting in the main parser, many per-type caps, incremental output limiting, contract-clean color/attribute sinks, bounded composite-glyph recursion, deterministic collections, and a last-resort inert-shell degrade.

The main risk is that the safety model is uneven at seams and before emission. Several legal, bounded inputs can still make work or retained output grow quadratically, and finite `double` inputs can overflow intermediate arithmetic into invalid geometry that the emitter silently rewrites to zero. The foreign math seam validates markup tokens but not document structure or box metrics. The play-through API limits each frame but not the number or aggregate size of retained frames.

I found no demonstrated script execution, network pivot, subprocess injection, production stream leak, or dangerous shared cache. The math-fragment finding is still important, but it should be described accurately: the current allowlists prevent the obvious executable SVG surface; the demonstrated failure is structural containment escape/malformed output, not an RCE.

The four highest-priority corrections are:

1. Add an aggregate/frame-count budget or streaming API to `renderFrames`.
2. Replace the math-fragment regex scanner with bounded structural validation and validate all fragment metrics.
3. Complete the already-owned global pre-emission layout/work budget.
4. Reject or safely normalize finite numeric magnitudes that overflow layout arithmetic.

## Finding index

| ID | Severity | Finding | Status / ownership |
|---|---|---|---|
| SIR-01 | High | `renderFrames` retains an unbounded aggregate of individually capped SVGs | Unowned |
| SIR-02 | High | `FragmentGuard` validates tokens, not balanced structure or wrapper containment | Unowned |
| SIR-03 | High | No global work/shape budget before emission | **Already owned:** `sirentide-layout-budget-oom-caps` |
| SIR-04 | High | Legal finite numbers can overflow into silently corrupted geometry | Unowned |
| SIR-05 | High | Anchor collision resolution is quadratic | Unowned |
| SIR-06 | High | Sequence-note placement is quadratic in messages × notes | Unowned |
| SIR-07 | Medium-high | Cyclic Sankey depth relaxation is `O(VE)` | Unowned |
| SIR-08 | Medium-high | Foreign math metrics are trusted without validation | Unowned |
| SIR-09 | Medium | Reversed Gantt tasks can lie outside the computed domain | Unowned |
| SIR-10 | Medium | Timeline collision rows use declaration order, not x order | Unowned |
| SIR-11 | Medium | Duplicate semantic parsers make accessibility disagree with the visual | Unowned |
| SIR-12 | Medium | Class/ER math fragments are rendered repeatedly during one layout | Partly adjacent to active self-loop work |
| SIR-13 | Medium | Parser/config/CLI input boundaries disagree | Unowned |
| SIR-14 | Medium | Public IR records do not consistently enforce immutability | Unowned |
| SIR-15 | Medium | “Math in every label-bearing type” is not true of the current implementation | Product decision |
| SIR-16 | Medium-low | Several capped parsers allocate all tokens before applying retention caps | Unowned |
| SIR-17 | Medium-low | Font outlines and format-12 cmap groups are repeatedly reparsed/searched | Adjacent to code-health plan |
| SIR-18 | Medium-low | Clone families, monoliths, dead branches, and mutable globals raise change cost | Partly known / partly unowned |
| SIR-19 | Test gap | Some resource tests are vacuous at the parser boundary | Unowned |

## Method and verification

This was a source-and-execution audit, not a style-only review.

- Updated refs and pinned the review to the exact baseline above in an isolated worktree; `main` was not modified.
- Inventoried 113 production Java files, 81 test files, 22 diagram types, and approximately 37.4 KLOC of production Java.
- Traced the four public bake pipelines, parser dispatch, every relevant layout family, SVG emission, a11y replay, CLI I/O, font decoding, and existing caps.
- Reviewed repository history and active Sirentide plans to distinguish new findings from already-owned work.
- Used three independent review lanes for performance, architecture/duplication, and correctness/reliability. I then inspected and reproduced every retained finding myself; unverified suggestions were discarded.
- Added temporary package-local probes, executed them, recorded their results, and removed them. No probe remains in the tree.
- Ran the complete non-browser Gradle test suite. `BrewShotGalleryTest` and `MathGalleryBrewShotTest` were intentionally excluded to avoid Chrome; no browser-backed test was launched.

### Quantitative receipts

| Probe | Size | Observed work / output |
|---|---:|---:|
| Duplicate anchor assignment | 500 | 124,750 failed `Set.add` probes |
|  | 1,000 | 499,500 |
|  | 2,000 | 1,999,000 |
|  | 4,000 | 7,998,000 |
| Sequence notes interleaved with messages | 100 + 100 | 10,100 note comparisons |
|  | 200 + 200 | 40,200 |
|  | 400 + 400 | 160,400 |
|  | 800 + 800 | 640,800 |
| Sankey directed cycle | 100 nodes/flows | 10,000 flow visits |
|  | 200 | 40,000 |
|  | 400 | 160,000 |
|  | 800 | 640,000 |
| Play-through sequence | 160 messages, 2 actors | 162 retained frames; largest 513,822 chars; aggregate 83,174,444 chars |

The play-through aggregate was **16.6×** the nominal 5,000,000-character per-output ceiling even though every individual frame was legal.

## Detailed findings

### SIR-01 — `renderFrames` has no aggregate retention budget

**Severity:** High

**Evidence:** `src/main/java/com/sirentide/api/Sirentide.java:101-153`, `:170-230`

`renderFrames` lays out once, discovers every distinct sequence anchor, emits one complete SVG per anchor, and retains every result in an `ArrayList`. It checks the base and each emphasized SVG against `MAX_OUTPUT_BYTES`, but it does not cap:

- the number of frames;
- aggregate UTF-8 or UTF-16 size;
- cumulative emitter work; or
- caller-visible retention cost.

A legal 160-message sequence produced 162 frames because messages and actor groups both carry sequence anchors. Every frame was below the 5 MB cap, but the returned list retained 83,174,444 characters. A larger legal diagram can consume hundreds of megabytes without violating an individual-frame check.

The same problem exists in `renderFramesWithDiagnostics`. `List.copyOf` protects list mutability, not aggregate memory.

**Recommendation:** Add both a maximum frame count and an aggregate UTF-8 budget. For larger narratives, expose a callback/iterator/streaming sink that emits one frame at a time. If the current all-or-nothing contract must remain, fail to a single inert frame before allocating the frame list when the predicted or running aggregate crosses the budget.

### SIR-02 — `FragmentGuard` does not prove structural containment

**Severity:** High

**Evidence:** `src/main/java/com/sirentide/contract/FragmentGuard.java:37-42`, `:61-120`, `:123-131`; `src/main/java/com/sirentide/layout/MathLabel.java:145-164`; `src/main/java/com/sirentide/emit/SvgEmitter.java:178-185`

The guard recognizes allowed tag tokens and allowed attributes, but it never validates an element stack. It also ignores the captured self-closing marker and permits arbitrary bracket-free text between tags. Consequences include acceptance of:

- unmatched or misnested opening/closing tags;
- a closing tag that escapes the emitter-supplied transform/fill wrapper;
- non-whitespace text and entity references;
- duplicate attributes;
- non-self-closed `path`/`rect` elements; and
- a fragment containing no actual element at all.

The following shape was accepted in the probe:

```xml
</g><path d="M 0 0 L 99999 99999" fill="#000000"/><g>
```

When embedded verbatim, it closes the wrapper early, draws outside the intended transform/fill containment, and reopens a group so the final output can remain superficially well formed. Mismatched tags, raw entity/text content, and other structural violations were also accepted.

The current element and attribute allowlists are valuable: I did **not** demonstrate `<script>`, event attributes, `href`, `foreignObject`, `style`, or external URL execution. The confirmed issue is that “allowed tokens” is weaker than “one structurally valid contained fragment.”

**Recommendation:** Use a bounded XML/token parser with DTDs and external entities disabled. Require:

- a synthetic single root around the fragment;
- a balanced element stack;
- only `g`, `path`, and `rect`;
- the existing exact per-element attribute/value allowlists;
- unique attributes;
- whitespace-only text nodes;
- `path` and `rect` to be empty elements; and
- a strict depth/node/attribute/character budget in addition to `MAX_FRAGMENT_LEN`.

If zero-dependency is non-negotiable, a small stack-based tokenizer is sufficient; it still needs to enforce structure rather than regex matches alone.

### SIR-03 — the output cap fires after layout has already done the dangerous work

**Severity:** High

**Evidence:** `src/main/java/com/sirentide/emit/SvgEmitter.java:116-127`; `src/main/java/com/sirentide/layout/FlowchartLayout.java:1324-1393`, `:1488-1608`

The incremental emitter cap limits the output buffer, but every layout has already materialized its `Shape` graph by then. Several legal expansions happen before that checkpoint:

- Flowchart routing mints one virtual waypoint for every skipped layer of every long forward edge.
- Back-edge routing allocates up to two rail candidates per non-endpoint node, sorts them, and can test each candidate against all node boxes. This work is performed in sizing/prepass and emission paths.
- Matrix/heatmap row and column caps multiply.
- Sequence blocks, notes, dividers, dotted pieces, and similar enrichments expand one IR item into many shapes.

The existing local caps are useful but do not compose into a total per-render limit. A type can remain under every local count while exceeding a safe global work/allocation envelope.

**Ownership note:** This is already the remaining slice of active plan `sirentide-layout-budget-oom-caps` (`fe8c5bbc-b6e6-422e-a738-16fc99910dee`, owner Confluence). Do not create a duplicate ticket.

**Recommendation:** Finish that plan as a general `LayoutBudget`, charged for shapes, virtual vertices, route candidates/tests, glyph work, and expensive iterations. The budget should be checked during layout, before allocation, and degrade deterministically. A shape-only counter would not cover the rail-search and relaxation cases.

### SIR-04 — finite input can overflow intermediate geometry

**Severity:** High

**Evidence:** `src/main/java/com/sirentide/layout/AxisScale.java:21-30`, `:57-73`; `src/main/java/com/sirentide/ir/Pie.java:48-58`; `src/main/java/com/sirentide/layout/PieLayout.java:76-105`; `src/main/java/com/sirentide/layout/SankeyLayout.java:92-136`, `:176-195`; `src/main/java/com/sirentide/emit/SvgEmitter.java:332-345`

The parsers generally reject literal `NaN`/`Infinity`, but “finite operands” do not guarantee finite arithmetic:

- `new AxisScale(-1e308, 1e308)` has an infinite span. Projection of the maximum becomes `NaN`.
- Two pie slices of `1e308` overflow `positiveTotal()` to infinity; both sweeps become zero.
- Two `1e308` Sankey inflows overflow node/column sums, making scale or node heights non-finite.

The emitter then maps non-finite geometry to `0`, converting a numeric failure into a deterministic but false diagram. The bake often looks valid and returns success rather than surfacing a bad range.

**Recommendation:** Define supported numeric magnitudes at the parse/IR boundary and enforce them. Where large magnitudes are useful, normalize before summation/division:

- scaled or compensated sums for pie/Sankey;
- overflow-safe projection such as normalized endpoints or half-range arithmetic;
- a final finite/non-negative geometry validation before emission.

Do not rely on emitter zero-clamping as a correctness path; reserve it as last-resort containment and report a diagnostic.

### SIR-05 — duplicate anchor assignment is quadratic

**Severity:** High

**Evidence:** `src/main/java/com/sirentide/layout/AnchorAssigner.java:16-36`

For each duplicate base id, `assign` starts suffix search at `-1` and retries every suffix already used. For `N` identical labels, the exact number of failed probes is:

```text
N × (N - 1) / 2
```

The probe confirmed 7,998,000 failed `HashSet.add` calls at only 4,000 items. The legal 10,000-row ceiling implies 49,995,000 failed probes, plus repeated string construction and truncation.

**Recommendation:** Keep `used`, and add a `Map<String,Integer>` named `nextSuffixByBase`. Start each duplicate at the remembered next suffix and advance only on a genuine collision with another sanitized base. Add a 10,000-identical-label regression that asserts near-linear probe count rather than a fragile wall-clock threshold.

### SIR-06 — sequence-note placement is quadratic

**Severity:** High

**Evidence:** `src/main/java/com/sirentide/parse/DslParser.java:1644-1665`, `:1733-1743`; `src/main/java/com/sirentide/layout/SequenceLayout.java:229-247`

The parser caps messages but appends valid notes without an independent note cap. Layout walks every message boundary `K = 0..messageCount` and rescans the entire note list to find notes at that boundary.

With `N` messages and `N` notes, the exact comparison count is `N × (N + 1)`. The probe reached 640,800 comparisons at 800 + 800; a legal 10,000 + 10,000 source is approximately 100,010,000 comparisons before normal layout/emission work.

**Recommendation:** Bucket notes by `atMsg` in one stable pass, or consume a list already sorted by `atMsg` with a cursor. Add an explicit note/enrichment cap or charge notes to the global layout budget.

### SIR-07 — cyclic Sankey relaxation is `O(VE)`

**Severity:** Medium-high

**Evidence:** `src/main/java/com/sirentide/layout/SankeyLayout.java:79-124`

Column assignment runs up to `V` passes over all `E` flows. A directed cycle changes on every pass, so it reaches the full bound. The probe confirmed exact square growth: 100→10,000, 200→40,000, 400→160,000, and 800→640,000 flow visits.

A legal 10,000-edge cycle therefore drives roughly 100 million passes through string-keyed map lookups. The final clamp makes output deterministic but does not reduce the work.

**Recommendation:** Map names to integer IDs once, compute strongly connected components, condense them into a DAG, then assign depth with one topological longest-path pass. As an interim safety measure, charge each relaxation visit to the global work budget.

### SIR-08 — `MathFragment` metrics cross the trust boundary unchecked

**Severity:** Medium-high

**Evidence:** `src/main/java/com/sirentide/api/MathFragment.java:3-17`; `src/main/java/com/sirentide/layout/MathLabel.java:43-76`, `:89-113`, `:145-164`

`FragmentGuard` inspects only `innerSvg`. `widthPx`, `heightPx`, and `depthPx` may be negative, `NaN`, infinite, or arbitrarily large. They directly affect label advances, box sizes, baselines, row heights, and canvas dimensions.

The probe supplied contract-clean markup with negative and `NaN` metrics; it was accepted. Downstream arithmetic then produces invalid geometry that can be clamped to zero by emission or distort unrelated layout.

**Recommendation:** Make `MathFragment` validate itself, or validate once in `renderGuarded`: all metrics finite, width/height/depth non-negative, and each plus total extent below documented limits derived from font size and fragment-size budgets. Reject the whole fragment to the existing raw-text fallback.

### SIR-09 — reversed Gantt tasks are excluded from their own domain

**Severity:** Medium

**Evidence:** `src/main/java/com/sirentide/ir/Gantt.java:25-42`; `src/main/java/com/sirentide/layout/GanttLayout.java:48-82`

The domain uses only the minimum of task starts and maximum of task ends. For normal `start <= end` ranges this is correct. With multiple reversed tasks it can omit endpoints entirely.

For tasks `100→90` and `80→70`, the computed domain is `80..90`; the first task starts at 100, so its x coordinate lies beyond the canvas. The width is then replaced by the three-pixel minimum marker, but at an off-canvas position.

**Recommendation:** Compute the global minimum and maximum across **both** endpoints of every task, or normalize each task’s endpoints before aggregation. Decide separately whether a reversed task should be a marker or a visually normalized interval.

### SIR-10 — timeline row de-collision contradicts its own x-order invariant

**Severity:** Medium

**Evidence:** `src/main/java/com/sirentide/layout/TimelineLayout.java:132-157`

`assignRows` documents “walk the labels in x order,” but iterates the original declaration order. Legal timeline values need not be sorted. With projected centers `[436, 44, 73.4]` and widths 40, it assigned rows `[0, 1, 1]`; the second and third labels overlap in row 1 even though row 0 is spatially free at the left.

**Recommendation:** Stable-sort indices by projected center, assign rows in that order, and write results back by original index. Add unsorted and equal-x tests. The two-row “least bad” policy for more than two simultaneous overlaps should also be documented explicitly.

### SIR-11 — accessibility reimplements visual semantics and has already drifted

**Severity:** Medium

**Evidence:** `src/main/java/com/sirentide/layout/GitGraphLayout.java:49-51`, `:93-105`; `src/main/java/com/sirentide/a11y/A11yDescriber.java:442-480`; `src/main/java/com/sirentide/parse/LabelRuns.java:6-16`, `:32-68`; `src/main/java/com/sirentide/a11y/A11yDescriber.java:945-962`

Two concrete divergences were reproduced:

1. GitGraph layout caps lanes at 40; the a11y replay has no corresponding cap. A 41-branch input drew 40 visual branch groups while the description said “across 41 branches.”
2. Visual label parsing uses `LabelRuns`, which understands escaped `\$`. A11y strips math using an unrelated regex. For `cost \$5 and $x$`, the description contained the corrupted text `cost \x$` and lost the literal price text.

These are examples of a broader pattern: the a11y layer independently replays domain rules instead of consuming a resolved semantic view.

**Recommendation:** Extract one resolved GitGraph replay model used by both layout and a11y. Generate spoken label text by walking `LabelRuns.Run` values rather than applying a second grammar. Add parity tests that compare visually retained entities/labels with described entities/labels at caps and escape boundaries.

### SIR-12 — Class/ER call the injected math renderer repeatedly

**Severity:** Medium

**Evidence:** `src/main/java/com/sirentide/layout/ClassDiagramLayout.java:147-220`, `:257-261`, `:471-529`, `:667-681`; `src/main/java/com/sirentide/layout/ErDiagramLayout.java:157-203`, `:469-522`; `src/main/java/com/sirentide/layout/MathLabel.java:43-76`, `:145-163`

Class and ER compute and store `MathLabel.Measured` values while sizing, but their emission helpers call `MathLabel.measure` again instead of emitting the stored measure. Self-loop labels are also measured in width/canvas/placement/emission paths.

The probes observed:

- class member label: 2 renderer calls;
- ER attribute label: 2 calls;
- class self-loop label: 4 calls.

This is both performance and correctness debt. The `MathFragmentRenderer` interface does not require purity or byte-identical results. An expensive LatteX render is repeated; a stateful renderer can return metrics during sizing and different markup/metrics during emission.

**Recommendation:** Resolve each authored math label once per bake and carry the `Measured` value through sizing and emission. A bake-local cache keyed by source + font size is also reasonable. Avoid a global cache of foreign renderer results.

**Ownership note:** Self-loop code is being touched by active Fixpoint branches; reconcile before assigning that subset. The general resolve-once seam is not currently owned.

### SIR-13 — input and CLI contracts disagree

**Severity:** Medium

**Evidence:** `src/main/java/com/sirentide/parse/DslParser.java:135-168`, `:272-330`; `src/main/java/com/sirentide/api/Sirentide.java:61-89`; `src/main/java/com/sirentide/cli/Main.java:30-38`, `:67-81`, `:102-120`, `:146-153`

There are three related boundary mismatches:

- `parse` enforces a true UTF-8 byte limit; `parseConfig` checks only UTF-16 `String.length()`. A multibyte source can therefore parse to `Empty` while its title/theme config still applies. The probe produced a zero-sized titled `role="img"` SVG from an over-byte-cap source instead of one coherent over-cap outcome.
- Raw stdin reads `MAX_SOURCE_BYTES + 1`, passes the over-cap string to `Sirentide.render`, and exits 0 with the inert shell. The documented CLI contract says over-cap input is a loud exit 2.
- `run` and `renderRawDsl` declare `IOException`. A failing stdin stream escaped instead of returning the documented author-facing error code. File-path conversion also has an uncaught runtime-error surface outside the `IOException` classification.

Repeated independent `parseConfig` and `parse` calls also duplicate splitting and permit future semantic drift.

**Recommendation:** Introduce one bounded parse result such as `ParsedDocument(config, diagram, diagnostics)` and make all public pipelines consume it. Make bounded stdin reading return an explicit `OK / OVER_CAP / IO_ERROR` result; map the latter two to exit 2 for the CLI while retaining the API’s inert-shell behavior. Catch invalid path/runtime boundary errors at the CLI edge.

### SIR-14 — the typed IR is not consistently immutable

**Severity:** Medium

**Evidence:** `docs/DESIGN.md:29-33`; `src/main/java/com/sirentide/ir/Matrix.java:21-31`; `src/main/java/com/sirentide/ir/Heatmap.java:26-36`; `src/main/java/com/sirentide/ir/XyChart.java:24-33`; `src/main/java/com/sirentide/api/FramesResult.java:3-10`; contrast `src/main/java/com/sirentide/ir/QuadrantChart.java:26-45`

The design promises a single typed immutable IR, but:

- Matrix and Heatmap make no defensive copies of outer or nested lists.
- `XyChart` copies the outer series list but retains and returns mutable `double[]` rows.
- `FramesResult` accepts a caller-owned mutable list when constructed directly.

The probes mutated XyChart series through both the original array and the accessor, and mutated Matrix/Heatmap data after construction. `QuadrantChart` demonstrates the correct pattern with constructor and accessor copies.

**Recommendation:** Deep-copy all mutable collections/arrays at construction and return immutable values or defensive clones. Add mutation-isolation contract tests for every public record containing a collection or array.

### SIR-15 — the documented math-label coverage is broader than reality

**Severity:** Medium

**Evidence:** `docs/DESIGN.md:63-70`; `src/main/java/com/sirentide/api/Sirentide.java:351-390`; `src/test/java/com/sirentide/MathInAllLabelsRealRenderTest.java:22-33`, `:94-114`

The design and dispatch comment say real math reaches every label-bearing type. Several shipped types deliberately keep labels plain or do not receive the renderer in their layout, including Journey, Mindmap, Sankey, Matrix, Heatmap, and TensorNetwork paths. A Journey label containing `$x$` invoked the supplied renderer zero times.

The test named `MathInAllLabelsRealRenderTest` covers a representative older subset, not all 22 types. Some exclusions may be intentional—identifiers and terse technical tokens need not be formulas—but that means the contract and test name are inaccurate.

**Recommendation:** Make a product decision:

- implement a shared label primitive and a diagram/label-position census test; or
- narrow the documentation to the actual supported label positions and explicitly list plain-identifier exclusions.

Do not infer coverage from accepting a `math` parameter; assert that the renderer is actually invoked for each promised position.

### SIR-16 — retention caps are applied after allocation-heavy splitting

**Severity:** Medium-low

**Evidence:** `src/main/java/com/sirentide/parse/DslParser.java:157-173`, `:430-453`, `:2299-2329`, `:2391-2425`, `:3354-3363`

The one-megabyte source ceiling bounds absolute damage, but several handlers call unrestricted `split` and then retain only the first 100–500 results. A near-cap single line containing hundreds of thousands of commas or spaces creates the complete token array and substrings before the loop notices its cap. The parser also splits the full source into all lines before dispatch.

**Recommendation:** Use bounded scanners or limited splitting that stops after the relevant cap. A small shared comma/whitespace token cursor would remove several allocations and reduce duplicate parsing code.

### SIR-17 — font work is repeated per occurrence

**Severity:** Medium-low

**Evidence:** `src/main/java/com/sirentide/font/FontMetrics.java:23-47`, `:137-155`; `src/main/java/com/sirentide/font/SfntMetrics.java:116-155`, `:230-240`, `:258-329`, `:401-412`

The bundled font object is correctly loaded once. However, each visible code point:

- searches format-12 cmap groups linearly;
- reparses `glyf`/`loca` contours;
- allocates new contour/point lists; and
- recursively reconstructs composite contours.

Repeated labels therefore redo immutable font work. The effect is amplified because labels are often measured and emitted separately, and SIR-12 can call the math/text path more than once.

**Recommendation:** Cache immutable glyph-id mappings and immutable contour lists per glyph inside the bundled font instance; binary-search format-12 groups. Bound cache cardinality to the font’s glyph count. Benchmark allocation and throughput before/after. This is adjacent to, but more specific than, the active font/code-health plan.

### SIR-18 — structural duplication and dead paths increase regression risk

**Severity:** Medium-low

**Evidence:** representative locations below

Confirmed maintainability debt includes:

- Four closely related public bake pipelines repeat parse/config/layout/caption/a11y/emit/cap/degrade logic in `Sirentide.java:52-89`, `:101-153`, `:170-230`, and `:277-320`.
- Diagnostic classification recognizes the emitter cap by substring-matching an exception message (`Sirentide.java:323-337`), coupling semantics to prose.
- `ClassDiagramLayout` and `ErDiagramLayout` are 872 and 849 lines and carry near-parallel grid, measurement, table/box, relationship, self-loop, and label logic. Their history shows paired fixes landing repeatedly.
- Matrix and Heatmap have parallel parser/layout families with similar row/column normalization.
- `DslParser` is 3,595 lines and owns dispatch plus 22 grammars; adding a type requires coordinated edits across the parser, sealed IR, layout dispatch, a11y dispatch, tests, and documentation.
- Flowchart sets `anchored = true` unconditionally (`FlowchartLayout.java:423-429`) but retains several never-taken anchored/non-anchored branches, including `:755-757` and `:863-894`.
- Class and ER retain unused private `measure` helpers.
- `FlowchartLayout.MAX_DASH_PIECES` is public and mutable solely for tests (`:65-71`), creating a process-global race if tests or callers mutate it during concurrent renders.
- Multiple local numeric `fmt` helpers and centered-label helpers implement the same policy independently.

**Recommendation:** Refactor only after active layout branches settle:

1. Extract one guarded bake context/result pipeline with typed cap exceptions.
2. Extract small shared class/ER primitives—resolved labels, grid placement, self-loop lane planning—without forcing both diagram semantics into one mega-engine.
3. Move test knobs into injectable package-local policy objects.
4. Remove unconditional dead branches.
5. Split `DslParser` by diagram grammar behind a small registry/dispatch seam.

Avoid a broad rewrite. The existing golden and mutation-oriented tests are valuable; use them to support narrow extractions.

### SIR-19 — resource and coverage tests miss important boundaries

**Severity:** Test gap

**Evidence:** `src/test/java/com/sirentide/TimelineOomGuardTest.java:48-63`, `:113-126`; `src/test/java/com/sirentide/PlayThroughFramesTest.java:35-57`; `src/test/java/com/sirentide/MathInAllLabelsRealRenderTest.java:22-33`

Two `TimelineOomGuardTest` fixtures described as legal 10,000 × 512-character diagrams exceed the one-megabyte source cap by several times. They parse directly to `Empty`, so they do not exercise timeline/pie layout or the render-level emitter-cap path claimed by their comments. The direct `SvgEmitter` cap test is valid.

Play-through tests prove behavior on seven frames but do not stress aggregate retention. Existing tests also lack:

- identical-anchor scaling;
- messages × notes scaling;
- cyclic Sankey scaling;
- foreign fragment structural negatives;
- foreign metric validation;
- full math-label position census;
- renderer call-count/idempotency tests;
- finite-extreme numeric cases;
- mutation isolation for every IR record; and
- CLI failing-stream/over-cap exit-code parity.

**Recommendation:** Keep stress fixtures below the parser limit and assert the parsed IR type/non-emptiness before claiming a layout path was tested. Put low-heap or scaling tests in a forked JVM where appropriate. Prefer counters/exact complexity assertions over wall-clock-only thresholds.

## Active-work reconciliation

The following work was active at audit time and should be reconciled before assignment:

| Work | Owner/state | Audit overlap |
|---|---|---|
| `sirentide-layout-budget-oom-caps` (`fe8c5bbc-b6e6-422e-a738-16fc99910dee`) | Confluence, In Progress | Owns SIR-03’s remaining global layout/work-budget slice |
| `sirentide-codehealth-fontfork-docdrift` (`35b453ba-08fe-4cc1-9802-b2b1cce70372`) | Fixpoint, Draft | Adjacent to SIR-17 and parts of SIR-18; already tracks palette/formatter/centered-label/font-reader/doc drift |
| `sirentide-play-through-reading-order` (`2dd7b418-3851-4453-8b2c-cd926a541bd6`) | Active plan | Sequence ordering semantics, but **not** SIR-01 aggregate frame retention |
| `fixpoint/selfloop-labels` | Active branch | Overlaps the self-loop portion of SIR-12/SIR-18 |
| `fixpoint/edge-findings` | Active branch | Reconcile before edge-layout refactors |
| `fixpoint/play-through-order` | Active branch | Reconcile before play-through ordering work |
| `confluence/edge-label-decollision-v3` (`ea20153b-e5ab-46fd-9752-edc2a251081a`) | Confluence, active/review requested; successor work to `confluence/flowchart-label-guard` | Reconcile before Flowchart edge-label or shared label-decollision changes |

No active owner was found for SIR-01, SIR-02, SIR-04 through SIR-11, the general portion of SIR-12, or SIR-13 through SIR-16/SIR-19.

## Positive findings and ruled-out concerns

- Core has zero runtime dependencies; LatteX is injected/test-scope.
- The bundled font is initialized once through the holder pattern.
- Production code does not launch subprocesses or browsers.
- CLI file streams use try-with-resources; no production stream leak was found.
- Deterministic ordering is generally deliberate: linked maps/sets, stable sorts, and no timestamps/randomness in bake paths.
- No dangerous mutable global cache was found.
- The incremental emitter output cap is real; its direct regression test is valid.
- Mindmap recursion, actor/lane counts, dotted-edge pieces, composite glyph depth, and many per-type row/item counts already have meaningful local bounds.
- `FenceExtractor` currently matches the Stafficy behavior reviewed in this audit. Future duplication drift remains a maintenance risk, not a current divergence.
- `FragmentGuard` blocks the obvious executable SVG vocabulary. The demonstrated defect is structural containment, not proof of code execution.

## Remediation order

### P0 — safety and boundedness

1. SIR-01: aggregate/frame-count budget plus streaming option.
2. SIR-02 + SIR-08: structural fragment validation and metric validation at one foreign-content seam.
3. SIR-03: finish the already-owned global `LayoutBudget`.
4. SIR-04: numeric magnitude contract and finite-geometry validation.

### P1 — remove legal-input quadratic paths and visible correctness drift

5. SIR-05: next-suffix anchor map.
6. SIR-06: bucket sequence notes.
7. SIR-07: SCC-condensed Sankey depth.
8. SIR-09/SIR-10: Gantt domain and timeline x-order corrections.
9. SIR-11: shared resolved semantics for visual/a11y.
10. SIR-12: resolve math fragments once.

### P2 — contract consolidation and maintainability

11. SIR-13: unified parsed-document/input result and truthful CLI errors.
12. SIR-14: enforce deep immutability.
13. SIR-15: decide and test the real math-label support contract.
14. SIR-16/SIR-17: bounded tokenization and font caches.
15. SIR-18/SIR-19: narrow extractions and non-vacuous stress/contract tests after active branches reconcile.

## Suggested first implementation slices

The safest initial queue is deliberately small:

1. **Foreign-fragment boundary:** one change set for stack-based fragment validation, metric validation, and negative tests.
2. **Play-through bound:** aggregate byte/frame caps, diagnostics, and a streaming sink design note.
3. **Linear-time quick wins:** AnchorAssigner suffix cursor and SequenceLayout note bucketing with exact counter tests.
4. **Numeric contract:** supported magnitude decision, stable pie/Sankey normalization, AxisScale extremes, and pre-emission finite assertions.

These slices are largely independent. The global layout-budget work should stay with its current owner, and class/ER/flowchart refactors should wait for the active layout branches to reconcile.
