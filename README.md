# Sirentide

**Living, narratable diagrams — baked to static SVG, no runtime JS.**

Sirentide is a clean-room, pure-Java, zero-dependency renderer that turns a small diagram DSL into inert static SVG *at build time* — designed to carry a **native effect/narrative layer**: diagrams you'll be able to *play through*, effects bound to the diagram's own meaning. The foundation for that layer already ships — stable **semantic anchors** baked into the SVG plus a baked-frame `renderFrames` play-through API — alongside real **LaTeX math baked into labels** (via its sibling, [LatteX](https://github.com/supsup/LatteX)).

It is not a Mermaid clone. Mermaid is a design reference; Sirentide takes the idea, drops the browser, and aims at the thing no browser-JS diagram tool can put in a CSP-clean static bake: a controlled, sanitizer-safe effect layer that will give a diagram *presence*.

## Why it exists

- **Static-site / docs safe.** Output is inert `svg/path/rect/line` geometry (labels baked to `<path>` glyphs) — no `<script>`, no runtime JS, no external fonts. It survives a strict HTML sanitizer untouched.
- **Hermetic build.** Pure JVM, zero runtime deps (no headless browser at bake). The one build-time asset is a bundled OFL/Apache font, rendered to paths.
- **Native effects (by design).** Sirentide already emits the SVG with its own stable semantic anchors, so the coming effect layer ("glow the critical path", "pulse the active step", "play the flow in order") will attach natively — not bolted onto a drifting class-soup.
- **Math composition.** A node label, axis tick, or bar can *be* a real LaTeX formula — its LatteX sibling renders it at bake, and the two minimal-alphabet emitters compose for free.

## Status

Live and shipping. The render pipeline (DSL → IR → layout → SVG), the clean-room font-metrics oracle, and **twenty-three diagram types** are built today — each baked to inert `svg/path/rect/line` geometry — plus **LaTeX math in labels** (the LatteX bridge), **semantic anchors** (`data-sirentide-role/id/seq`), the **baked-frame play-through API** (`renderFrames`, with a `renderFramesWithDiagnostics` twin that adds a why-did-it-degrade channel without touching the never-throw bake), and a live **`/docs` integration**: a ```` ```sirentide ```` fenced block in a docs page bakes to a sanitized inline diagram. **See [examples/showcase.html](examples/showcase.html)** — every type + the one-bake-any-theme demo, all live renderer output. Every shipped sealed-IR type also has a browser-audited BrewShot reference in the [gallery](examples/gallery/GALLERY.md), enforced by a headless type-to-capture coverage test.

The six flagship types, in detail:

| Type | One-liner | Sample |
|---|---|---|
| **`pie`** | proportional wedges; optional `legend` (alias `key`), per-item `#hex` colours (3- or 6-digit), thin-slice outside labels clamped | `pie legend`<br>`"Reviews" : 40`<br>`"Docs" : 30 #22c55e` |
| **`xychart`** | signed bars on a fractional-clean axis | `xychart`<br>`"Mon" : 5`<br>`"Tue" : -3` |
| **`timeline`** | events placed *proportionally* in time; bare years and ISO dates both shown as dates, labels ellipsized/clamped | `timeline`<br>`"Founded" : 2000`<br>`"Launch" : 2020` |
| **`gantt`** | tasks on a shared, min-normalized time axis; degenerate domains still draw markers | `gantt`<br>`"Design" : 0-3`<br>`"Build" : 3-8` |
| **`flowchart`** | `TD`/`LR` directed graph, `A[rect]`/`A{diamond}` nodes, `-->|label|` per-hop edge labels, chained `A-->B-->C`, cycle-tolerant with visible back-edge lanes | `flowchart TD`<br>`A[Open PR] --> B{Approve?}`<br>`B -->\|yes\| C[Merge]` |
| **`sequence`** | actors + time-ordered messages: `->>` calls, `-->>` replies, self-messages | `sequence`<br>`Client ->> Auth : login`<br>`Auth -->> Client : ok` |

Plus seventeen more: **`state`** (rides the flowchart engine), **`quadrant`**, **`classDiagram`** (all five UML relationship markers), **`erDiagram`** (crow-foot cardinalities), **`gitGraph`**, **`journey`**, **`mindmap`**, **`sankey`**, **`mathblock`** (standalone display LaTeX), **`matrix`** (comparison / verdict grid), **`heatmap`** (continuous 0..1 cells on a sequential ramp, with a legend), **`snake`** (continued-fraction snake graph), **`tensornetwork`** (Penrose MPS/MPO), **`young`** (integer-partition boxes), **`dynkin`** (semisimple Lie-algebra classification), **`rootsystem`** (deterministic finite-root-system Coxeter-plane projections), and **`knot`** (classical knots — trefoil/unknot/figure-eight). The flowchart carries the full mermaid node-shape set, edge styles, and nested subgraphs; the sequence diagram carries `alt`/`loop`/`par` frames plus activation bars.

Cross-cutting: a `color=` header modifier for off-slice text, `currentColor` theme-adaptive labels, per-diagram themes, and hard input caps so a malformed or oversized source degrades to an inert shell — the bake never throws. A `$…$` fragment inside any label typesets as real math via the LatteX bridge.

Still ahead (the remaining *thesis* work): the native **effect layer** — `data-sirentide-fx`, the security-gated Part 2 the anchors were built to carry — see [SLOWSTART.md](SLOWSTART.md).

## Docker

Build the Java 25 image from the repository root:

```sh
docker build -t sirentide .
```

The image keeps its application artifacts under the immutable
`/opt/sirentide` tree and runs as the non-root `10001:10001` user by default.
The existing one-shot CLI remains the default entry point, so the original
stdin-to-stdout flow is unchanged:

```sh
printf '%s\n' 'pie' '"Reviews" : 40' '"Docs" : 60' \
  | docker run --rm -i sirentide > diagram.svg
```

`cli` is an optional explicit spelling for scripts that want to distinguish
one-shot work from the folder worker. File input can be mounted read-only while
the output mount stays writable:

```sh
mkdir -p Input Output
docker run --rm \
  --user "$(id -u):$(id -g)" \
  --mount type=bind,src="$PWD/Input",dst=/sirentide/input,readonly \
  --mount type=bind,src="$PWD/Output",dst=/sirentide/output \
  sirentide cli render /sirentide/input/diagram.md \
    -o /sirentide/output/diagram.svg
```

### Watched input and output folders

Run a long-lived worker with writable input and output mounts:

```sh
mkdir -p Input Output
docker run -d --name sirentide-worker --restart unless-stopped \
  --user "$(id -u):$(id -g)" \
  --mount type=bind,src="$PWD/Input",dst=/sirentide/input \
  --mount type=bind,src="$PWD/Output",dst=/sirentide/output \
  sirentide watch
```

The examples map the process to the current host UID/GID so ordinary bind
folders stay writable on Linux. If your container runtime already translates
ownership, omit `--user` and retain the image's non-root `10001:10001` default.
Prefer either approach over world-writable permissions. Watch mode accepts
complete direct children of `/sirentide/input` with these extensions:

- `.md` and `.markdown` — render the first ```` ```sirentide ```` fence, using
  the same fence extraction, source cap, and render outcome as the CLI.
- `.sirentide` — render a raw Sirentide DSL source.

Hidden names, temporary uploads, symlinks, other extensions, and the worker's
state directories are ignored. Producers should write a hidden sibling and
rename it into place only when complete, for example:

```sh
cp diagram.md Input/.diagram.md.tmp
mv Input/.diagram.md.tmp Input/diagram.md
```

For `diagram.md`, a successful job creates `Output/diagram.md.svg` and moves
the source to `Input/finished/diagram.md`. The full lifecycle is:

```text
Input/diagram.md
  -> Input/processing/<job-id>/diagram.md
  -> Input/finished/diagram.md
```

A failed render instead writes a bounded, content-free
`Output/diagram.md.error.txt` and moves the source to
`Input/failed/diagram.md`. Existing outputs and archived inputs are never
overwritten; conflicting archives are retained below a job-id directory in
`finished/collisions` or `failed/collisions`. A file left in `processing` by a
stopped container is recovered on restart. Multiple workers may share the same
mounts: atomic claims plus idempotent publication ensure one final state even on
bind-mount drivers that do not coordinate advisory file locks.

An eligible file that the non-root worker cannot open follows the same failed
lifecycle instead of stopping the watcher: its inode is moved without reading
or copying the source bytes, one bounded diagnostic is emitted, and later jobs
continue normally. When a same-name failed archive already exists, the
unreadable source remains below its job-id directory in `failed/collisions`.

The worker never lengthens the original name inside a path component. If the
source name fits the mounted filesystem but adding `.svg` or `.error.txt` would
not, the corresponding output uses a bounded `job-<job-id>` filename instead;
the source is still archived under its unchanged original name. A diagnostic
collision uses a bounded job-and-attempt filename and never overwrites the
existing file.

Set `SIRENTIDE_WATCH_POLL_MS` to an integer from `10` through `60000` to change
the scan interval (default `500`). The watched input mount must be writable so
the worker can move jobs between state folders; the one-shot CLI input mount can
remain read-only.

## Docs

- **[QUICKSTART.md](QUICKSTART.md)** — render a diagram in five minutes.
- **[SLOWSTART.md](SLOWSTART.md)** — the why, the differentiators, the milestone ladder.
- **[RELEASE_NOTES.md](RELEASE_NOTES.md)** — what's shipped, dated.
- **[docs/DESIGN.md](docs/DESIGN.md)** — full design, the LatteX dependency, the security model.
- **[examples/gallery/GALLERY.md](examples/gallery/GALLERY.md)** — real-browser renders of every shipped diagram type, captured by [BrewShot](https://github.com/supsup/BrewShot) and checked against the sealed production IR inventory.

## Family

- **[LatteX](https://github.com/supsup/LatteX)** — the clean-room LaTeX→SVG math sibling Sirentide depends on (render-only) and composes with.

## License

Apache-2.0.

## Disclaimer

This project is provided under the Apache License 2.0 on an "AS IS" basis, without warranties or conditions of any kind. See the LICENSE file for details.
