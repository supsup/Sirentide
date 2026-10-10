# Sirentide — Release Notes

Sirentide turns a small diagram DSL into clean, self-contained **SVG** — pure Java, zero
dependencies, safe to drop straight into a web page, no runtime JavaScript. New to it? See
**[QUICKSTART.md](QUICKSTART.md)** to get going and **[SLOWSTART.md](SLOWSTART.md)** for the why.

---

## **0.6.0** — IN PROGRESS

Development after the immutable 0.5.0 release belongs to the 0.6.0 line. No
new feature is claimed by this version boundary alone; reviewed entries will be
added here as they land. Source-checkout jars now identify as 0.6.0 so they
cannot be mistaken for the published 0.5.0 artifacts.

- **The image now carries `org.opencontainers.image.revision` and `.version` labels**, so
  `docker inspect` shows which commit and release an image was built from without unzipping the
  jar. The revision is the already-required `SIRENTIDE_SOURCE_REVISION`; the version comes from
  `SIRENTIDE_VERSION`, defaulting to the build version, and the build now refuses a value that
  differs from the jar manifest's `Implementation-Version`.

- **Two mermaid spellings that used to degrade now render: `graph` and `xychart-beta`.**
  `graph` is mermaid's original flowchart keyword and still the most-copied one in the wild;
  `xychart-beta` is mermaid's actual spelling for the chart Sirentide already drew as
  `xychart`. Both previously fell through the header match and degraded, so a diagram pasted
  from mermaid's own docs lost its shape for a reason the author could not see. Both are pure
  aliases onto shapes that already existed — no new renderer, no new grammar.
  **The README's capability claim was corrected in the same change**, which is the point:
  the old line overstated what Sirentide draws, so adding two spellings without fixing it
  would have widened a claim that was already too wide. It now says **8 of 14** mermaid
  shapes, NAMES the four that are unsupported, and states that activation bars are consumed
  but not drawn. A capability ships with its own honesty fence or it ships a lie.

- **Heatmap: outlined cells, categorical palettes, custom ramps + bins, hideable headers**
  (plan f4d69e44). All opt-in, so every existing heatmap renders byte-identically:
  a cell token ending in `!` is outlined (`4:0.125!`); `palette: C1 #4e79a7, C2 #f28e2b, S`
  makes cell values category NAMES rather than magnitudes, with a swatch legend (hex-only
  colours; a MISSING colour takes the default categorical palette, an INVALID one takes the same
  default and is reported); `ramp: #fff7ec, #d7301f, #7f0000` replaces the default blue ramp;
  `bins: 8` or `bins: 0.25, 0.5` steps the fills and the legend; `hide: rows`, `hide: cols` or
  `hide: both` drops the row-label column or the header band. In a palette entry the LAST
  whitespace token is the colour, so a multi-word category name with no colour must be quoted
  (`"big win"`); unquoted, `big win` is category `big` with the invalid colour `win`. Quoted
  names and quoted categorical cells may contain commas. **Malformed directive input never fails
  the bake and is never silent:** a bad `ramp:` stop (or one past the 16th, or fewer than two
  valid), a rejected `bins:` token, an unknown `hide:` target, a bad, nameless or duplicate
  `palette:` entry, and a categorical cell naming no category each fall back to a default AND add
  a line-scoped caveat (`line 3: ramp: "red" is not a #hex colour; stop ignored`) that
  `sirentide render <file.md>` prints and `--strict` fails on. **Two spellings changed meaning:** a cell token that ends in `!` used to
  show the `!` and is now an outlined cell, and an UNQUOTED row labelled `palette`, `ramp`,
  `bins` or `hide` is now a directive (quote the label to keep it a row), the same rule
  `cols:` and `scale:` already followed.

- **`%%` comments are documented.** They have worked for some time and appeared in no
  author-facing page: not QUICKSTART, not the README, not the docs site. A `%%` line draws
  nothing and a diagram containing one renders byte-identically to the same diagram without it.
  The note also states the property that motivated blanking comments rather than removing them —
  an error further down still reports its **real source line number**, so a comment above a
  mistake does not shift the diagnostic away from the line you are looking at — and distinguishes
  the leading `%%` configuration block from a `%%` line in the body.

- **`render` now says when a diagram rendered but lost a line.** The directive-shape rule drops
  an unknown directive-shaped statement and records a line-scoped caveat on an otherwise-`OK`
  render, specifically so a lost line is not lost silently — but the caveat lived only in the API.
  Through the `render` verb, which the authoring docs name as *the* local check, an author saw
  exit `0`, no output, and a diagram quietly missing their line. A caveat channel nothing reads is
  not a channel. The verb now prints `sirentide: rendered, with caveats — dropped statement(s): 1;
  line 3: <the statement>` to stderr, naming the statement that vanished rather than only
  reporting that one did. The exit code stays `0` and the SVG is still written: the render
  genuinely succeeded and `/docs` genuinely serves it, so failing here would claim a bake outcome
  that does not happen. A diagram that lost nothing stays silent — asserted by its own control,
  because a warning that fires on every render is noise an author learns to ignore.
  **`--strict` promotes such a caveat to exit `1`** for unattended callers (ruling
  `PROJECT/sirentide` 977): stderr is the right author channel and the wrong CI channel,
  because CI is exactly where nobody reads stderr, and a caveat that cannot gate anything
  in the one environment that runs unattended is recorded-but-unseeable one level up. The
  flag is opt-in so the default stays honest, it does **not** manufacture a failure on a
  clean render, and unlike the exit-`1` unrenderable arm the SVG **is** still written —
  there the artifact would be a lie about what `/docs` serves, here it is exactly what
  `/docs` serves and a caller whose gate just rejected something wants to see it.

- **An xychart now names the x-axis labels it could not fit, and `render - --strict` now
  fails on caveats.** Each category label is fitted to its column; a label too wide is
  shortened with an ellipsis, and when not even the ellipsis fits it is drawn as *nothing*. A
  crowded chart lost labels silently: a 25-bar autocorrelation chart (lags `-12..12`) drew
  only the ten one-character lags and still reported `"Rendered successfully."`. Such a render
  is now an `OK` render with a caveat that lists the **dropped** and the **shortened** labels
  by name (detail `xychart category-label drop: dropped -12; …`), the same shape as the pie
  thin-slice caveat. The SVG is byte-identical; only the verdict gained the sentence.
  Separately, **`render -` (DSL on stdin) used to ignore every caveat**: it never printed one
  and `--strict` exited `0` on a render that had dropped statements or labels, while
  `render <file.md> --strict` failed on the same source. Both arms now report caveats
  through one shared seam, so `--strict` means the same thing on either. The two framing
  lines printed under a caveat no longer speak of "statement(s)" when the caveat is about a
  label: they now read `the SVG is what /docs would embed; it does not show what the caveat
  names as written` and `--strict: treating the caveat as a failure`.

- **`xychart … numeric` puts the x axis on a number line** (plan c880b12e). A new header
  modifier, beside `line`/`scatter`/`legend`: `xychart line numeric` (or `scatter numeric`;
  bare `numeric` is a line) reads each row as `x : y1 y2 …` and draws it at its x on a
  continuous axis, instead of in an evenly spaced category column whose label may not fit. Both
  axes take nice ticks (1, 2 or 5 x 10^k), **thinned** to the finest step whose labels all fit
  whole, so a tick label is never shortened or dropped: the 25-lag Barker chart now draws the 13
  lags `-12, -10, …, 12` and passes `--strict`, where the category chart dropped 15 of 25. Tick
  labels are **plain decimals on both axes** (`0.0005`, never `5.0E-4`), and discs shrink with
  density so a 140-point series reads as a curve, not a smear. Every row the numeric parse cannot
  draw as written is **named with its line** on the OK caveat (`xychart numeric row issue(s)`),
  so `--strict` fails on it: a non-numeric, non-finite or out-of-range value (magnitude 0 or
  1e-12..1e15), a row with no `:` or no y value, a late `series:` row, a row with more values
  than `series:` names or fewer than the series count, and on a line a repeated x (the later row
  is dropped) or an out-of-order x (the line is drawn sorted by x). A scatter takes repeated and
  unsorted x as written. A value written as a nonzero number that underflows to 0 (`1e-400`) is
  out of range too, not drawn at 0; `0`, `-0`, `0.000` and `0e5` are zero. **Gaps:** in a chart
  with more than one series, a y value written `na` (exactly, lowercase) says that series has no
  value at that x: no point is drawn there and its line is broken (the runs either side are not
  joined), while the other series draw as usual. `na` is silent, since the author said so; a
  short row, which leaves the same gap unexplained, keeps its caveat. `na` as the only series'
  value or as an x is refused with a caveat (a gap needs another series to be missing beside),
  and `NA`, `Na` or `-` are not gaps but non-numeric values, caveated as such. Opt-in: every
  chart without `numeric` renders byte-identically, and
  the default category y axis still prints its ticks the way it did, E-notation included (left
  for a separate ruling, because changing it moves the bytes of existing charts).

- **`render --math` typesets `$...$` labels from the CLI.** Until now only a Java API caller who
  supplied a renderer got typeset math; through the CLI a label like `$\sqrt{2}$` baked as its raw
  source and was then ellipsized like any other text. `--math` loads LatteX at run time from
  `--lattex PATH` or `SIRENTIDE_LATTEX_JAR`, in an isolated class loader, and proves the load with a
  probe render first. Sirentide still bundles nothing: `--math` without a jar, or with one that
  cannot render, is a loud usage error (exit `2`) before any bake, never a silent raw-text fallback.
  A run LatteX cannot typeset is drawn as its raw source **and named on stderr** as a caveat, so
  `--strict` fails on it. The output is byte-identical to the API with the LatteX renderer; without
  the flag every byte is unchanged.

- **`render --batch` renders many raw DSL sources in one JVM.** Sources are NUL-separated on stdin
  (a diagram is multi-line, so a newline cannot delimit it) and each produces exactly one
  NUL-terminated record on stdout, in order: the SVG `render -` would print for that source alone,
  or a `sirentide: error: ...` record in its slot. Record N always answers source N, including a
  blank source (a legal empty diagram) and an over-cap source (an error record; the batch reads
  past it and stays aligned). stderr names failures and caveats by record number; exit `1` if any
  record failed or, under `--strict`, carried a caveat. `-o` and `--png` are refused with
  `--batch` rather than silently ignored.

- **An xychart whose `$...$` category labels are lost is now named, not silent.** The label-loss
  caveat skipped math labels on the theory that math is never ellipsized, which holds only when a
  math renderer is active. Without one (the default bake, or `render` without `--math`), a math
  label is drawn through the same ellipsize as plain text, so 30 categories labelled `$x_{i}$`
  drew no labels at all while `render - --strict` exited `0`. The check now follows the renderer:
  without one, math labels are measured as the text actually drawn and their loss is reported;
  with `--math` they are typeset, and one that fails to typeset is the untypeset-math caveat. The
  SVG is unchanged; only the caveat is new.

- **`render --lint-overlap` (and `RenderOptions.lintOverlap` on the API) checks for colliding
  text.** Opt-in and off by default, so no existing render gains a caveat. After a successful bake
  it compares every text run's outline box with every text run in a *different* diagram element
  (or outside any element) and reports each overlapping pair on the caveat channel as
  `text overlap: ...`, so `--strict` fails on it only when the flag is set. The SVG is never
  changed. Text is compared with text only, so an edge label on its own stroke is never a
  finding; typeset math fragments are not checked. Measured on the 32 golden fixtures, one fires:
  `sequence-blocks`, where the `loop` label "every retry" sits 1.8 px into the "ping" message
  below it.

- **Which Sirentide baked an SVG is answered by the jar, not the output.** `render`'s usage text
  now says so: the baked SVG carries no renderer, revision or source attribute, and the jar's exact
  source revision is the `Sirentide-Source-Revision` line of its `META-INF/MANIFEST.MF`.
  `--source-hash` (below) identifies the source, on stderr; it never identifies the renderer.

- **`render --source-hash` prints the SHA-256 of the source to stderr.** One line,
  `sirentide: source sha256:<64 lowercase hex>`, or under `--batch` one
  `sirentide: record N: source sha256:<hex>` line per record (N 1-based, blank records included),
  printed before that source's other diagnostics. **Nothing is added to the SVG** (ruling
  `PROJECT/sirentide` 1129): with or without the flag, stdout, the `-o` file and the exit code are
  byte-identical, and `--strict` means what it meant. The hash is of the **raw bytes as received**,
  never a decoded copy: every byte of stdin for `render -` (`sha256sum < diagram.dsl` recomputes
  it), each record's bytes between NULs for `--batch`, and for `render f.md` the fence body as the
  file holds it (body lines with any trailing CR, joined by LF, without the LF that ends the last
  line). So CRLF, a BOM and invalid UTF-8 are part of the identity, and a CRLF copy of a diagram
  hashes differently from its LF copy; a hash of the decoded text would have changed silently
  wherever the input is not valid UTF-8. The line prints for a source that does not render too,
  because it identifies the input, and is absent only when there is no source (usage error, no
  fence, unreadable file). An over-cap stdin, of which only a prefix is read, says `unavailable`
  rather than hashing the prefix; an over-cap `--batch` record is read to its NUL anyway, so it
  is hashed in full.

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

### A `%%` comment in the diagram body no longer renders as a node

**Behaviour change, and it is scriptable.** Mermaid's comment syntax is `%% text`. Sirentide
honors `%%` in the *preamble* as its config channel, and used to hand a `%%` line in the
**body** straight to the type parser — which parsed it as a lone node declaration. The most
copied line in any mermaid snippet became a drawn, labelled box wearing the comment as its
name, and the render reported `outcome=OK`.

Measured across types before the fix, so the scope is a fact rather than a guess: **flowchart,
`stateDiagram-v2` and `mindmap` all leaked** the comment into the diagram; `sequenceDiagram`
and `pie` already dropped it. The fix is therefore at the shared body seam rather than in one
type parser — all three public entry points (`parse`, `detectUnsupportedConstruct`,
`flowchartBodyCensus`) now take the body from one producer, so they cannot disagree about what
a body *is*.

```
flowchart TD
    %% the happy path
    A[One] --> B[Two]
```
renders two nodes. It used to render **three**, the third named `%% the happy path`.

**Comments are blanked, not removed**, and that is deliberate: diagnostics report 1-based
*physical* line numbers, so deleting the line would silently shift every later line's reported
position — a diagnostic pointing at the wrong line is worse than one pointing nowhere. A
comment on line 2 leaves a bad statement on line 3 still reported as line 3.

**A comment is not a dropped statement.** It carries no "statement was dropped" caveat, because
it was never a lost statement — it is intentional syntax. A body of *only* comments declares
nothing and reports success, exactly as an empty body does.

### An unknown directive no longer mints a node wearing its own text as a name

**Behaviour change, and it is scriptable.** A line shaped like a directive this parser has
never met — a bare first word followed by a `key:value` payload — used to parse as a *node
declaration*, so the diagram grew a phantom box with CSS for a name and returned
`outcome=OK` with `"Rendered successfully."`. That is how `classDef critical fill:#fee2e2`
rendered as a labelled box on a parser built before `classDef` existed. It is the failure
mode you hit every time the DSL grows a keyword your vendored jar predates.

Such a line is now **dropped and named**, and the rest of the diagram still renders:

```
$ printf 'flowchart TD\n    A[One] --> B[Two]\n    quuxStyle zork fill:#f00\n' | sirentide
(SVG written — A and B render normally)

Rendered successfully. Note: 1 statement was dropped from this body and did not
render; line 3 uses a directive-shaped line whose keyword this parser does not
know (a bare first word carrying no `[`/`{`/`(`/`"` delimiter, followed by a
`key:value` payload) — it would otherwise mint a node wearing its own directive
text as a name.
```

Note the verdict still opens with **"Rendered successfully."** — the caveat is appended to
it, never substituted for it. That is the whole shape of an OK caveat: a script checking
the exit code or the `outcome` field sees no change, while a human or a log reader gains
the sentence that was missing.

`outcome` stays **`OK`** and the SVG is real content — this is a *caveat on a success*,
not a refusal. A diagram must not go blank because one line was unreadable, so the drop is
line-scoped and composes with the existing pie and font-coverage caveats rather than
replacing the verdict.

**The rule is deliberately narrow**, and the two edges are worth knowing:

- A multi-word bare line stays a node. `Two Words Bare` is legal here — this parser
  diverges from mermaid at exactly that point, and an earlier, wider design was refuted by
  the test corpus. The `key:value` payload is what separates a CSS-carrying directive from
  an ordinary multi-word label.
- **A payload-LESS directive still mints.** `animate fast` has no `key:value`, so by shape
  alone it is indistinguishable from a legal multi-word node. That residual is the cost of
  the narrowness, it is stated rather than hidden, and closing it needs the vendored-jar
  version-skew work, not a wider guess here.

Known keywords are unaffected: `classDef`, `class`, `style`, `click`, `direction`,
`accTitle:`/`accDescr:` keep their own existing treatment, so "we do not know this keyword"
is never said about a keyword we do know.

### A flowchart that renders empty no longer reports success

**Behaviour change, and it is scriptable — read this if you pipe Sirentide.** A flowchart
whose body has statements but produces no nodes and no edges used to return `outcome=OK`
with `"Rendered successfully."` and exit `0`. The author got a blank picture and a success
verdict; every automated check passed and only a human looking at the image could tell.

Such a body now degrades and says which line went, and why:

```
$ printf 'flowchart TD\n    A <--> B\n' | sirentide
sirentide: diagram did not render — This flowchart's body has 1 statement but produced
no nodes and no edges, so the diagram rendered empty: line 2 uses a bidirectional arrow
(`<-->`, `<-.->`, `<==>`), which the Sirentide DSL does not support, so that whole
statement was dropped. (nothing written)
$ echo $?
1
```

- **A genuinely empty body is still a success.** `flowchart TD` alone, a blank source, or
  whitespace keeps `outcome=OK` and exit `0`. The trigger is *a non-empty body that yields
  an empty graph*, never merely an empty graph — calling every empty diagram a failure would
  be a worse bug than the one this fixes, and it is pinned by a test.
- **The SVG is untouched.** Rendered bytes are byte-identical before and after across every
  probed source; only the diagnostic verdict and the exit code changed.
- Covered drop paths include the bidirectional arrow forms, unparseable lone-node and
  endpoint declarations, an unclosed edge label, and bodies that declare nothing renderable
  (`direction`-only, `classDef`-only, an empty `subgraph`).

Two limits stated rather than implied: this covers **flowcharts only** — other diagram types
can still render an empty scene from a written body and report OK — and a **partial** drop
(one line lost while others render) still reports OK, because the trigger is emptiness rather
than "something was dropped".

### Fixed

- `render-png` now refuses an unrenderable diagram instead of writing a PNG beside
  a failed SVG. The no-args and `render -` paths previously diverged: one refused,
  the other continued to the write tail, so the same bad input could leave an
  artifact on disk or not depending on how it was invoked. Both now pass through a
  single refusal seam and reach one shared write tail, making the two forms
  equivalent by construction rather than by matching code.
- **Correction to the `graph` / `xychart-beta` entry above:** sequence activation bars ARE
  drawn, and were when that entry said they were "consumed but not drawn". Activation is
  implicit (a call `->>` activates its callee, a reply `-->>` ends it, nested activations
  stack), and the mermaid sigils `->>+` / `-->>-` are accepted and change nothing. The README
  and a parser comment now say so; the entry above is left as written, as the record of what
  was claimed.

## 2026-07-30 — Release **0.5.0**

The version moved to 0.5.0 immediately after the 0.4.0 cut so post-release jars could never be
mistaken for the immutable, already-vendored `sirentide-0.4.0.jar`. This cut freezes that accumulated
surface: the `sirentide render <file.md>` parity checker and Docker watch flow, trusted frame-deck
budgets with artifact-paired optional assets, deterministic packing and self-loop layout hardening,
fail-closed display-label diagnostics, and one new diagram type — `rootsystem` — growing the sealed
production inventory **22 → 23**. The detailed contracts and degradation boundaries follow below.

### Source-verifiable immutable artifacts

The executable jar now carries `Sirentide-Source-Revision`, an exact lowercase 40-hex git commit,
beside its implementation version. The release-only
`SIRENTIDE_REQUIRE_CHROME=1 ./gradlew releaseBuild` gate removes old build output, rejects an
unresolvable revision, a dirty worktree, a non-`X.Y.Z` version, unfinished release notes, or a build
where the real-browser pins can skip. It then runs the full build and verifies the executable,
sources, and Javadoc jars; the paired `sirentide-frames.js` / `.css` resources; manifest version and
source identity; and a SHA-256 sidecar for each jar. The tag and GitHub release are therefore bound to
artifacts that can be traced back to one reviewed source commit.

### Each self-loop label rides ITS OWN loop (plan 64cf1bae, reviews sirentide/761 + 768 + 770)

A stacked class/ER self-loop label now tells you which loop it names. Every label sits in ONE
column just past the node's outermost lane leg — clear of every lane line whatever the label's
width — and its BASELINE is aligned with its own loop's top horizontal leg, so it reads as
riding that loop the way an edge label rides its edge. The earlier cut of this work x-staggered
the labels one lane-pitch apart, which separated them but tied none of them to its loop.

Placement is a **contract hierarchy**, not one rule with silent exceptions, and both
degradations are named:

- **Ideal** — the baseline sits on its own loop's top leg, in the constant label column. This is
  what you get whenever nothing else binds.
- **Degradation 1, corridor.** A neighbour edge crossing the label column outranks exact
  leg-association: a label sitting on that edge would read as a label *of* it, which is worse
  than being a few px off its own leg. The move is **per label** (`SelfLoopLabelColumn`), never a
  shift of the whole set: the labels share an x ORIGIN, not an x EXTENT, so a crossing edge can
  run through a wide label's band and miss a narrow one entirely. Only a label whose OWN corridor
  is crossed moves, and only by the minimum that clears it; a label whose corridor is clear — and
  whose leg still fits under the label above — sits EXACTLY on its own leg. Where clearing
  downward is the only escape, the metric floor carries that move to the labels below it: a
  cascade, minimal, and contract too.
- **Degradation 2, metric floor.** Consecutive baselines are separated by the upper label's real
  DESCENT plus the lower label's real ASCENT plus a 2px gap, measured per label (inline math
  included), so the occupied bands are disjoint by construction at any label size. A tall label
  can push the lanes below it off their legs; order is preserved. This replaces a fixed
  one-line-slot budget that ignored what a label actually measured — two tall math fragments
  used to emit overlapping bands while every receipt stayed green.

Both are solved ONCE in the layout pre-pass, from the full label set (floor first, then the
per-label corridor placement over the floored stack — a forward pass in leg order followed by a
backward relax toward the ideals), and canvas reservation and emission consume that single result
— nothing is recomputed at emit. `SelfLoopGeometryTest` pins the ideal, each degradation, and
their COMPOSITION on the stacked fixture (leg association for the unconflicted labels, clearance
for the conflicted ones, the exact cascade minimum, order and pairwise disjointness together);
gallery references `class-self-loop*` and `er-self-loop*` re-captured.

### A dropped thin-slice pie label is NAMED, never silent (plan 86cee1d3)

A pie slice too thin for its outside leader-label used to drop the label with no signal — a
coloured wedge with no name and a clean OK. `renderWithDiagnostics` now reports the drop as an
OK-with-caveat naming the slice and pointing at `pie legend`, which shows every label in the
side key (new gallery twin `pie-thin-labels-legend`). The SVG bytes are unchanged — the caveat
rides alongside, never in the bake. The drop caveat **composes** with the font-coverage caveat
through one carrier (`okDiagnostics` now delegates to `withFontCoverageCaveat`), so at their
intersection both honest notes appear instead of the last-built one shadowing the other
(pinned by a delete-mutant-verified discriminator).

### Tag-shaped display labels now FAIL CLOSED

A label like `A[TRUE NEGATIVE<br/>safe to act on]` used to render `<br/>` as **visible text**
with exit 0, a well-formed SVG, and no diagnostic — every automated check passed and only a
human looking at the picture could tell. Sirentide renders label text literally and has never
supported HTML; the defect was that saying so silently is indistinguishable from succeeding.

Tag-shaped markup in a **display label** is now a parse-level failure: `render` degrades to the
inert shell, `renderWithDiagnostics` reports `PARSE_ERROR` at stage `parse` naming the label's
stable identity and a bounded token, `renderFrames` behaves identically, and
`sirentide render <file.md>` exits **1** and writes nothing — matching what the /docs bake
already does with a fence that will not render.

**Compatibility boundary, deliberately narrow.** Two things stay legal and are pinned by
controls:

- **Ordinary comparison prose.** `x < y`, `a <- b`, `3<5` and `0<x+y>1` all render. The check is
  tag GRAMMAR — a name that terminates at whitespace, `/` or `>` — not "contains a bracket".
- **Inline math on the surface that supports it.** `$…$` in a flowchart node label is still
  rendered through the math renderer and is not scanned as markup.

The math exemption follows **rendering semantics**, not the label text: surfaces that emit plain
glyph paths (config caption, GitGraph labels, mindmap nodes) are scanned in full, because on
those surfaces `$…$` has no math meaning and would otherwise be a delimiter that smuggles markup
past the check.

**Identifiers are unaffected.** `subgraph outer<unsafe> [Outer title]` still sanitizes the id to
`outerunsafe`; only the visible `Outer title` is validated.

**The element name is the XML QName production**, not a list of characters. The first cut
enumerated letters, digits and hyphen, so `<b>` and `<x-custom>` refused while `<svg:rect/>`,
`<xhtml:br/>`, `<_priv>`, `<x_y>` and `<v1.2>` still rendered as visible text with a clean OK —
the original defect on a tag variant the check was not written against. A namespaced element is
not exotic: SVG, the output format, *is* XML. The name is now `NCName (':' NCName)?` over ASCII,
with `NCName` = `[A-Za-z_][A-Za-z0-9_.-]*`.

This is a QName and deliberately **not** "a colon is one more name character", because
`<http://example.com>` is the ordinary way to write a URL in prose. What follows the colon must
itself start an NCName, so bracketed URIs stay legal — `<http://example.com>`,
`<https://x.example/p?q=1>`, `<file:///tmp/x>`, `<mailto:bob@x.com>`, `<tel:15551234>` and
`<doi:10.1000/xyz>` all render, and so do `<u,v>` and `<a|b>`. Non-ASCII names such as `<área>`
are **not** refused: widening to Unicode letters would reject ordinary bracketed words in
ordinary prose for a shape neither HTML nor SVG uses in practice.

Still rendered literally, pending a contract call: comments, declarations and processing
instructions (`<!-- c -->`, `<!DOCTYPE html>`, `<![CDATA[x]]>`, `<?xml ...?>`). These are markup
but not *tags*, which is what the policy is scoped to.

### Trusted frame-deck budgets and artifact-paired optional assets
Sirentide now exposes additive trusted-consumer input
`FrameBudget(maxFrames, maxUtf8Bytes)` and a bounded
`renderFramesWithDiagnostics` overload. Both limits must be positive and may only
narrow Sirentide's independent 512-frame / 50-MB producer defenses. The frame-count
gate runs before any emphasized frame is emitted; exact UTF-8 bytes are checked
prospectively before each completed frame is retained. A consumer-cap hit returns
an empty deck plus typed `OUTPUT_CAP_EXCEEDED` diagnostics identifying either
`consumer-frame-count` or `consumer-utf8-bytes`. Existing render and frame overloads
retain their byte-compatible behavior and degrade shapes. The bounded overload also
applies a final invariant fence to every frame-bearing return, including parse or
unsupported diagnostics, producer-cap degrades, and caught fallback frames: a
sufficient budget preserves the original frames and diagnostic, while an insufficient
budget retains no frame.

The same jar now carries optional `sirentide-frames.js` and
`sirentide-frames.css` bytes through defensive `FrameDeckAssets` accessors. The
runtime enhances only conformant wrappers with multiple direct-child SVGs, builds
native Previous/Next controls from fixed text, and uses no HTML sink, inline handler,
author data, or inner-SVG vocabulary. Its stylesheet is inactive until enhancement,
so absent or failed JavaScript leaves every inert frame visible in source order.
Sirentide itself neither injects nor executes these assets; ordinary SVG rendering
remains zero-runtime. The exact `sirentide-frames` fence, document-wide 32-frame /
4-MiB consumer budget, same-origin routes, page injection, CSP proof, and live docs
remain gated Stafficy work. No sanitizer or emitted-SVG contract grows here.

### Deterministic Timeline and GitGraph displayed-label packing
Displayed Timeline event/value labels and GitGraph commit-ID labels now use a
compatibility-gated interval partition over their actual post-clamp emitted
boxes. Existing clean diagrams retain their SVG bytes exactly. When a Timeline
band or GitGraph branch lane would overprint, the earliest-finishing-row rule
allocates the minimum deterministic row count; Timeline shifts/grows its axis
and canvas, while GitGraph carries added label depth into later lane baselines,
spines, and branch/merge connectors. The parser's 10,000-item bound feeds only
linear retained placement state, with no silent display-row cap or overflow
stack. This is deliberately a narrow claim about those displayed labels — it
does not claim every Sirentide label or whole diagram is overlap-free, and it
does not change Flowchart's separate actual-box decollider.

### Docker CLI and watched folders
Sirentide now ships a multi-stage Java 25 Docker build with immutable application jars under
`/opt/sirentide` and a non-root runtime. The original one-shot CLI remains the image's default
entry point (with `cli` as an optional explicit mode), while `watch` adds a long-running folder
flow over `/sirentide/input` and `/sirentide/output`. Complete `.md`, `.markdown`, and
`.sirentide` inputs are atomically claimed into `input/processing`; successful sources move to
`input/finished`, failures move to `input/failed`, and outputs or bounded diagnostics land in
the output mount. Publication never overwrites an existing output or archived source, abandoned
processing claims recover on restart, and concurrent workers converge on one final state even
when a bind-mount driver does not coordinate advisory file locks. Claim IDs and original source
names occupy separate path components, and job-id fallbacks bound derived output and diagnostic
names when appending a suffix would exceed a mounted filesystem's component limit.
Unreadable eligible inputs now receive a bounded failed disposition without copying or exposing
their bytes, and the watcher remains live to process later jobs. Their original inode stays in a
durable `failed/pending` state until diagnostic publication succeeds, so a cleared output-mount
fault is reconciled on restart without overwriting an existing diagnostic or failed archive.
Shared watchers now reconcile a vanished unreadable processing path against the exact
snapshotted inode in pending, collision, and direct failed dispositions. A same-name
unrelated archive cannot certify completion, while losing workers stay live and continue
with later jobs.

### BrewShot gallery coverage ratchet
The real-browser example gallery now photographs the shipped `young` diagram with BrewShot, closing
the one missing reference among Sirentide's 22 production diagram types. A headless drift guard derives
the authoritative type set from the sealed `Diagram` IR hierarchy and requires each type to map to a
declared gallery specimen, parse back to that exact IR class, have a committed non-empty PNG, and appear
in the generated gallery page. Aliases may share their canonical IR representative; a newly shipped type
can no longer leave the README's every-type gallery claim silently false. No production rendering
behavior changed in this audit.

### Bounded layout hot paths
Duplicate semantic-anchor suffix assignment and sequence-note placement now run in linear work, while
Sankey column relaxation has a deterministic 250,000-edge-inspection ceiling. Sequences accept 10,000
notes; the first valid excess note now crosses parsing as a bounded rejection marker and aborts before
caption, title, or theme decoration, yielding the literal inert SVG shell plus a named sequence-note-cap
diagnostic.

### Deterministic finite root-system Coxeter-plane projections
The additive `rootsystem` type renders every root of `Aₙ/Bₙ/Cₙ/Dₙ` through the explicit rendering
cap `n ≤ 24`, plus `E6/E7/E8`, `F4`, or `G2`, as a point in a deterministic Coxeter (Petrie) plane,
with concentric distinct-radius guides and `edges: minimal|none`. Weyl-reflection closure and the
exponent-1 Coxeter eigenspace are computed from the same refactored Dynkin/Cartan authority used by
`dynkin` — no copied coordinate or matrix table, RNG, network, or runtime dependency.
The shared public Dynkin/Cartan catalog now enforces its established inclusive rank-200 Dynkin
boundary before bond/matrix allocation or arithmetic, and uses checked count/Coxeter/label
arithmetic as defense in depth. The more expensive `rootsystem` consumer retains its independent
rank-24 closure/pair-work cap. Its block parser is permissive around prose and malformed type
candidates: the first valid type wins; a recognized invalid `edges:` directive still rejects the
block because that vocabulary is closed.
Rank/root/reflection/pair-work caps keep the bake bounded; an over-dense minimal graph degrades
all-or-none to points/rings with `edges:none`, and the accessible description names the cap rather
than silently drawing a partial graph. Malformed and over-cap types use the universal inert shell.
Guide rings are distinct projected radii, not generically one ring per Coxeter orbit: separate orbits
can coincide radially (A3 has three h=4 orbits but only two radii, with root multiplicities 8 and 4).
The E8 showpiece does satisfy the stronger oracle: eight distinct rings of 30 roots.
Semantic minimal links now use a one-pixel `#8490a1` stroke (3.24:1 non-text contrast against white)
while retaining their `edge` anchors. The complete E8 minimal figure is intentionally static-only:
its 6,720 edge anchors plus 240 point anchors make 6,960 play-through steps, above the shared
512-frame cap, so `render` succeeds while `renderFrames` fails closed to its documented inert frame.
Use `edges:none` or a smaller type when a root-system play-through is required.

### Deep code-audit reconciliation
The repository now carries Marlow's source-level audit of the 2026-07-23 baseline, reconciled against
current main after independent reproduction of all 19 findings. The report distinguishes historical
receipts from present code state, records the focused remediation merges that have already landed,
corrects severity ratings, and keeps the remaining global-work-budget and contract/test gaps explicit.

### Cluster and axis semantic anchors
The final two contract-reserved roles now have producer coverage. Every drawn flowchart subgraph frame
emits one `data-sirentide-role="cluster"` group keyed by its stable subgraph id. Eight primary axis
spines emit `role="axis"`: x/y for xychart, quadrant, and journey, plus the single time axis in timeline
and gantt. All groups share their diagram's existing id sanitizer, collision namespace, and contiguous
emit-order sequence; no SVG element, attribute, or value grammar was widened.

### Flowchart convergent-edge label de-collision (plan ea20153b part 2)
Two labeled edges reaching the **same target** from nearby sources used to place their labels at
nearly the same spot, so their rendered glyph **boxes overprinted in both axes** into an
unreadable mush — the plan's real info-loss case (two transitions into one state had to *drop* a
label to stay legible). The label pass now de-collides on the **actual rendered box** (not the
anchor point): keyed by shared target, when a label's box overlaps one already placed for that
target it is **stacked one line (`EDGE_LABEL_SIZE * 1.5`) below**, greedily fanning further
convergent labels down, and skipping any slot that would drop a label onto a **node box**. Labels
to different targets — or already separated in x or y — are untouched, so every non-colliding
diagram stays **byte-for-byte identical** (all pre-existing flowchart goldens unchanged; one new
`flowchart-convergent` golden pins the stacked output). A **deep** convergent fan (a realistic
sink/error state) can stack far enough to reach the canvas edge, so when the lowest stacked label
would fall past the bottom the canvas **grows in height to contain it + margin** — mirroring the
existing frame/back-edge canvas grows, and firing **only on genuine overflow** so no
non-overflowing diagram's canvas moves (review sir/523; one new `flowchart-convergent-fan` golden
pins the grown output — five labels into one sink, canvas height 168 → 202, every label
in-canvas). This revives the de-collision that was withdrawn on the "x-separated by construction"
argument: that premise was **measured false** (guard `convergentLabelsArePairwiseXDisjoint` at
confluence/flowchart-label-guard @ 277f3f1c — two convergent labels overprinted ~14px in x AND
~5px in y) and **retracted at sirentide/514** (anchor-x separation is not rendered-box-x
separation). The ported guard now passes green, order-independently.

---

## 2026-07-22 — Release **0.4.0**

Version bump **0.3.0 → 0.4.0** (to be vendored into stafficy `/docs` as `sirentide-0.4.0.jar`,
part B). One new diagram type — the type count grows **21 → 22** — plus a release-hygiene guard.

### The `heatmap` type (21 → 22)
A continuous-score grid: the comparison matrix's exact frame (grammar, caps, rectangularization,
coordinate-anchored cells, single-backing-rect gridlines) where each cell carries a **0..1
magnitude** — decimal, `NN%`, or `text:value` display override — filled by piecewise-linear
interpolation along a **single-hue sequential blue ramp** (`#eff6ff → #93c5fd → #1e40af`;
sequential-not-rainbow by design, disjoint from the matrix verdict palette). Non-numeric cells
fail closed to the neutral fill; dark-end cells flip their label to white via the shared
contrast rule; a sampled-step **ramp legend** (`scale: "low" --> "high"` names its ends) sits
under the grid as plain rects — **no new SVG element or attribute**, the output-contract
alphabet is unchanged. Fuzz census covers the type with a seed + a hostile-label template
(row label AND scale end); per-cell semantic anchors follow matrix's exact rule, so the FX
layer works unchanged. Reviewed at sir454/455 (adversarial ramp-lerp read, INV-4 legend
containment, artifact-provenance check); landed as `b39581f4` with 732/0/0/0 required-Chrome
plus a CI-scope `gradle build` at the tip.

### Release-hygiene guard
A new `ReleaseDocVersionPinTest` asserts QUICKSTART's build-recipe jar pin equals the gradle
project version, so a cut can no longer ship a stale recipe (the class of drift the 0.3.0
review found by hand across 7 doc sites). `docs/DESIGN.md:72` is deliberately excluded — it
names the *vendored* (stafficy-side) jar and lags by design until each part-B re-vendor lands.

---

## 2026-07-21 — Release **0.3.0**

Version bump **0.2.0 → 0.3.0** (to be vendored into stafficy `/docs` as `sirentide-0.3.0.jar`). The
headline is **five new diagram types** — the type count grows **16 → 21** — plus a semantic **oracle**
for the knot family.
All new types bake to the same minimal, sanitizer-safe `svg` / `g` / `path` / `rect` / `line` alphabet;
no new element or attribute shape reaches the emitter, so each is contained by the same construction as
the existing types.

### Five new diagram types (16 → 21)
- **`snake`** — the continued-fraction / square-snake graph (canonical Çanakçı–Schiffler construction),
  with a dimer/perfect-matching count as its semantic oracle.
- **`tensornetwork`** — Penrose MPS/MPO tensor-network diagrams (cores, bond edges, physical legs).
- **`young`** — Young diagrams (a partition rendered as its row-of-boxes tableau).
- **`dynkin`** — the finite Dynkin diagrams (A/B/C/D/E/F/G Cartan families), degrading malformed /
  unknown / over-cap types to the universal inert shell.
- **`knot`** — knot-projection diagrams (unknot, trefoil, and the figure-eight `4₁`), drawn as
  crossing-gapped closed strands.

### The knot Gauss-code oracle
The `knot` type ships with a geometry-derived **Gauss-code oracle**: it reconstructs the knot's Gauss
code from the *emitted* strand geometry (over/under derived from whether a strand reaches or gaps a
crossing) and asserts it equals the canonical code — a real discriminator for a valid double-point
projection, not a happy-path golden. Six review rounds hardened its path recognizer against the
browser/oracle lexical-divergence class (structure, relative commands, mid-arc closepath, hexadecimal
coordinates, and Java-only whitespace separators) so a mutation a browser renders differently can never
false-green.

### Also
- Flowchart router node-collision avoidance; self-loop marker pitch; empty-node single-band rendering.
- Documentation freshened to match (type counts, emitted-surface contract, the LatteX `0.6.0` math seam).

Measured artifact-to-artifact, `0.2.0 → 0.3.0` is a **type-surface** release: five more diagram types,
each contained by the same minimal-alphabet construction as the existing ones, and a semantic Gauss-code
oracle for the knot family. (A fuzzed geometry-containment trust floor across all types is in review and
will land in a following release.)

---

## 2026-07-17 — Release **0.2.0**

Version bump **0.1.0 → 0.2.0** (commit `829aba0`), vendored into stafficy `/docs` as
`sirentide-0.2.0.jar`. Measured **artifact-to-artifact** against the vendored `0.1.0` jar (which
already carried all fifteen pre-matrix types, `renderFrames`, the M1/M2 flowchart + sequence
surfaces, dark theming, and semantic anchors), notable `0.1.0 → 0.2.0` consumer-visible gains —
**highlights, not a binary-delta census** — include:

- the `matrix` comparison/verdict grid — the 16th type (see below; it degrades to the inert
  `0×0` shell on `0.1.0`),
- the `%% direction:` directive,
- semantic anchors on `matrix`,
- the six per-path OOM caps (their own dated entry below),
- the `%% caption:` band (a captioned diagram grows its canvas for the band; `0.1.0` renders
  the same input uncaptioned at the bare canvas size),
- `classDef` fills actually applied (a `classDef critical fill:#ff0000` node keeps the default
  fill on `0.1.0`),
- `renderFramesWithDiagnostics` overloads on the public `Sirentide` API,
- further behavior fixes recorded in the dated entries below (label wrapping, self-relation
  corrections, …).

**Not in the shipped `0.2.0` jar:** subgraph-id edge routing merged to mainline *after* the
`0.2.0` vendor (jar `11:25`, routing merge `13:39` the same day) and is unreleased until the next
cut — its dated entry **below** describes mainline, not this artifact.

### `matrix` — comparison / verdict grid (16th type)
A `matrix` diagram renders a labelled comparison grid: row/column headers and cells, with a
`text:verdict` cell syntax for a descriptive cell carrying its own colour. Cells bake to the same
`<rect>` + glyph-`<path>` alphabet every other type uses — no new element or attribute shape
reaches the emitter, so it is sanitizer-safe by the same construction as the existing types.
(Sixteenth verified against the parser dispatch at this tip: exactly sixteen diagram kinds,
`matrix` the newest.)

## 2026-07-17 — An edge to a subgraph id routes into the cluster

An edge whose endpoint names a **subgraph** used to mint a separate empty node wearing the
group's name — a phantom. Now it routes into the cluster:

> `flowchart TD`
> `EPR[Scaffold] --> PROJ`
> `subgraph PROJ [Project]`
> `PP[Package] --> QQ[Queue]`
> `end`

the `EPR --> PROJ` arrow points at the cluster's representative member (its first-seen member,
`Package`) instead of drawing a stray "PROJ" box. Routing is symmetric — a subgraph id on the
source side retargets too. An edge to an **empty** subgraph (no members, no representative) drops
whole — loud-or-dropped, never a phantom. A cluster id that is *also* a real, explicitly-declared
node (a `PROJ[Real]` box sharing a subgraph's id) keeps its node and its edges — only a bare
phantom routes. A genuine member edge, and a literal `A --> A` self-loop, are unchanged; a
flowchart with no edge-to-subgraph-id bakes byte-identically.

## 2026-07-17 — Robustness: six per-path resource caps (no diagram can OOM the renderer)

A hostile or accidental mega-input can no longer drive the layout into an out-of-memory blowup
before the 5 MB emit cap fires. Each element-multiplying path is now bounded at parse/layout time,
loud-and-visible (the excess drops; a diagram past these bounds is unreadable anyway):

- **Dotted/dashed edges** — `MAX_DASH_PIECES` (1000): a canvas-spanning dotted edge (× up to
  `MAX_EDGES`) no longer strides millions of `<line>`s before the emit cap.
- **Class / ER member rows** — `MAX_DISPLAYED_ROWS` (30) + a synthesized `… (N more)` row: a box
  near the parser's member ceiling stops growing a canvas-blowing tower.
- **Sequence message labels** — `MAX_MSG_LABEL_W` (220): a message across distant actors (a wide
  span) no longer admits a 512-char run; the **math (`$…$`) path** is bounded too — an over-wide
  formula degrades whole to its ellipsized source (it can't be cut mid-run), so a wide composite
  can't render span-independent thousands of px.
- **Matrix columns/cells** — `MAX_COLUMNS` (200): `cols: a,a,…×500k` (or a 500k-cell row) no longer
  forces a cols×rows grid that OOMs before layout.
- **XyChart series** — `MAX_SERIES` (100): each per-row value token is a series; a 500k-token row
  no longer explodes the legend + per-row bars.
- **Embedded math fragments** — `MAX_FRAGMENT_LEN` (64 KiB): a giant composite fragment is bounded
  before it reaches the sanitizer.

Every drop is at the parse/layout boundary, so nothing new reaches the emitter — the sanitizer
surface is unchanged. Each cap carries a mutation-surviving DoS regression; the visual ones are
BrewShot-verified.

## 2026-07-17 — Matrix semantic anchors (the queryable skeleton reaches the last element type)

`matrix` was the only element-bearing diagram with zero semantic anchors. Each data cell now emits
a closed `data-sirentide-role="cell"` + a coordinate-derived id + a row-major `data-sirentide-seq`,
completing the "semantic skeleton, nothing executable" invariant across every type. A hostile cell
label (`<script>…`, an `onerror` img) is pinned to appear **XML-escaped** in the output — the
non-vacuity guard proves the label→escaping-sink path, not merely "no live tag" (which a dropped
label would also satisfy).

## 2026-07-17 — `%% direction:` now steers a flowchart

The config-block directive `%% direction: TD|LR` was parsed but inert — a bare `flowchart`
header ignored it and always laid out top-down. It now drives the layout:

> `%% direction: LR`
> `flowchart`
> `A[Parse] --> B[Layout] --> C[Emit]`

lays out left-to-right, exactly as `flowchart LR` would.

**Precedence — an explicit header token always wins.** `flowchart LR` stays LR and
`flowchart TD` stays TD regardless of any `%% direction:`; the directive is only a *fallback*
for a bare `flowchart`. An unknown value (`%% direction: sideways`) leaves the `TD` default.
The axis-less types (sequence, pie, …) ignore direction and bake **byte-identically** — a
flowchart with no `%% direction:` block is unchanged too.

---

## 2026-07-12 — Node & edge styling (`classDef` stroke/colour + `linkStyle`)

Flowchart nodes and edges can now carry colour, not just a fill.

### `classDef` gains `stroke`, `stroke-width`, and `color`
A class definition already set a node's `fill`; it now also sets its border and its label
colour. Assign it with `class` exactly as before:
> `classDef critical fill:#fee2e2,stroke:#dc2626,stroke-width:2px,color:#7f1d1d`
> `class PayGate,Refund critical`

A node without a class keeps **no border** — every pre-existing bake is byte-identical.

### `linkStyle` — per-edge colour and width
Colour or thicken specific edges by their authoring index (0-based), or every edge with
`default`; an explicit index wins over `default`:
> `linkStyle 0,2 stroke:#dc2626,stroke-width:3px` &nbsp;·&nbsp; `linkStyle default stroke:#94a3b8`

### Safe by construction
Every colour is validated hex-only at the parse boundary (the same guard `fill` uses) and
every width is a bounded finite number (0–40); anything else is **dropped to the default**,
never forwarded. Borders emit into the existing `rect`/`path` alphabet — no new sanitizer
surface, so a styled diagram is as inert on `/docs` as an unstyled one.

## 2026-07-04 — M0 foundation & the first diagram

The project is born and building. This is the M0 foundation plus the first rendering diagram type.

### The render pipeline
The full bake path is in place: **DSL → parse → immutable IR → pure layout (→ coordinates) →
pure emit (→ SVG string)**, over a single shared IR that every diagram type projects into. The
emitter targets a deliberately tiny, sanitizer-safe alphabet and formats numbers deterministically
(byte-identical bakes).
> `Sirentide.render("pie\n \"A\" : 60\n \"B\" : 40")`

### `pie` — the first diagram
A pie chart renders end to end: the own-DSL `pie` form parses to slices, layout turns magnitudes
into angular wedges (pure arithmetic — no graph optimization), and emit serializes each to a
contract-clean `<path>`. A single-slice pie draws a full disc; malformed rows are skipped rather
than failing the bake.
> `pie` &nbsp;·&nbsp; `"Reviews" : 40` &nbsp;·&nbsp; `"Builds" : 30`

### Font-metrics oracle
A clean-room, metrics-only sfnt reader (`head`/`maxp`/`hhea`/`hmtx`/`cmap`) plus a layout-facing
oracle: `advance`, `runWidth` (surrogate-safe), `lineHeight`, and greedy word-wrap → `TextBox`.
Deterministic, no DOM — this is the text measurement label layout needs. The bundled label font
is **STIX Two Math** (OFL), reused from LatteX so labels and (soon) embedded formulas share one
face.

### Foundations
Zero-runtime-dependency Java 25 build (mirrors LatteX), CLI (stdin → stdout), and the founding
[`docs/DESIGN.md`](docs/DESIGN.md) — thesis, the LatteX dependency model, the per-element anchor
security model, and the milestone ladder.

**Planned next:** text labels as paths (glyph-outline reader), `xychart`, a minimal `sequence`
with a play-through, the native effect layer, and the LatteX-math-in-labels composition.

---

## 2026-07-07 — M1: the type explosion, the math moat, and semantic anchors

The DSL grew from one type to **eleven**. Beyond the M0 `pie`, the value/temporal family
(`xychart`, `timeline`, `gantt`), the graph family (`flowchart`, `sequence`, `state`, `quadrant`),
and the structured family (`classDiagram` with all five UML relationship markers, `erDiagram` with
crow-foot cardinalities, `mathblock` standalone display math) all bake end to end.

### The math moat
`$…$` inside **any** label-bearing type is handed to the injected LatteX renderer and baked to real
glyph paths — same layout tree as the SVG, per-label fail-soft (a math failure degrades that one
label, never the bake). Braced/multi-span LaTeX is handled span-aware.

### Semantic anchors + a11y
A closed, typed, value-constrained anchor vocabulary (`data-sirentide-role/id/seq`) is emitted on
inner `<g>` elements across all element-bearing types — the "semantic skeleton, nothing executable"
invariant. Every SVG also carries deterministic `role="img"` + `<title>`/`<desc>` a11y, and a
`renderWithDiagnostics` author-facing side-channel explains any silent label degrade.

### Flowchart clusters
`subgraph … end` titled bounding boxes, with nesting.

## 2026-07-08 — M2: full flowchart fidelity, four more types, theming & play-through

Four more types — `gitGraph` (commit lanes + merges), `journey` (satisfaction map), `mindmap`
(indentation-defined tree), `sankey` (weighted flows in depth columns) — bring the built total to
**fifteen**.

### Full flowchart fidelity
Mermaid node shapes (stadium · circle · hexagon · cylinder · subroutine · rounded) and edge
variants (open link · dotted · thick, each with its own style + arrow IR).

### Theming & config block
A `%% key: value` config block (`title` / `theme` / `direction`) plus theme palettes and a
self-contained background rect, so one bake serves any theme.

### Baked-frame play-through
`renderFrames(seq → N static SVG frames)` — a step-reveal without runtime JavaScript. Tall-fragment
box growth lands multi-row math in labels and roomier class/ER geometry.

### Hardening
A fuzz/invariant pass over all fifteen types pins three universal invariants (every drawn element
stays inside its declared canvas — the visual class the byte-pinned goldens can't see).

## 2026-07-09 — `/docs` integration live

A ```` ```sirentide ```` fenced block in a Stafficy `/docs` page now bakes to a sanitized inline
diagram (vendored jar + converter, mirroring LatteX). BrewShot bumped 0.1.0 → 0.6.0 for crisp
gallery capture. The container contract now distinguishes Sirentide's narrow producer output from
Stafficy's broader generic safe-SVG sanitizer, with enum- and prose-backed drift guards.

## 2026-07-10 — diagnostics twin for play-through

`renderFramesWithDiagnostics` — a why-did-it-degrade channel for the frame bake, without touching
the never-throw contract of `renderFrames`.

## 2026-07-11 — annotations, semantic colour & label wrapping

### Node-label word-wrap
A `flowchart` node label wider than `MAX_LABEL_W` (180px) now word-wraps to up to three lines and
its box grows to fit, instead of ellipsizing to one line. Every wrapped line is ellipsized to the
same bound (a no-op when it fits) so a spaceless label — a URL, or a single word wider than the
bound — clips cleanly instead of overflowing the box. A single-line label is byte-identical to the
pre-wrap engine.

### Caption / note band
`%% caption: <text>` (alias `%% note:`) renders a centered, word-wrapped annotation band **below any
diagram type** — one post-layout seam, wired into all four render paths. The caption bakes to
`currentColor` glyph `<path>`s (the exact shape every label uses), so it is inert by construction
and needs no sanitizer change. A diagram with no caption is byte-identical to the pre-feature bake.

### Semantic colour classes
`classDef <name> fill:#rrggbb` + `class <id> <name>` colour node box fills on `flowchart` and
`state` — the green=allow / red=deny / amber=decision palette the security diagrams need. Class
fills go through the same `#rrggbb`-only hex gate as a per-node colour, so no new value shape
reaches the emitter; resolution order is per-node `#hex` > class fill > header `nodecolor=` >
default. A per-node colour still wins over its class.

---

## 2026-07-14 — self-relations rendered right (class + ER)

A relation from a thing to itself (`A <|-- A`, `EMPLOYEE ||--o{ EMPLOYEE`) now renders as a
deterministic rectilinear **loop** off the box's right edge — previously the degenerate zero-length
edge drew its marker inside the box, and the interim fix erased the relation entirely. Four review
rounds of geometry hardening (plan `sirentide-correctness-selfrel-caption`, Lattice-reviewed):

### Self-loop lanes that cannot collide
The row cursor reserves each box's full loop **lane** — legs plus the widest measured label — so a
loop label can never escape the viewBox or run through the neighboring box. Multiple self-relations
nest in distinct lanes (each vertical leg one step further out), and the box **grows** so lanes
never clamp together into overpainting collinear legs: every authored relation keeps rendering.
Labels stack one line-slot apart above the loop, clear of the box-center band where a crossing edge
lives. Math-label ascent/descent participate in canvas growth.

### Marker ownership follows the authored operand
A whole/parent kind (`<|--`, markerAtLeft) caps the loop's TOP attach; an arrow kind the BOTTOM —
mirroring both the straight-edge rule and ER's left-cardinality-at-top mapping.

### Oracle receipts
`SelfLoopGeometryTest` bounds the FULL leaf geometry (every glyph/marker path coordinate, not just
line endpoints), rejects any positive-length collinear overlap between edge groups, and pins
pairwise-disjoint label boxes at four lanes. Real-browser gallery captures: `class-self-loop`,
`class-self-loops-stacked`, `class-self-loops-three`, `er-self-loop`.

### Caption single-word overflow
A single word longer than the caption wrap width hard-ellipsizes instead of escaping the canvas.
