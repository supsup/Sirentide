# Sirentide — Release Notes

Sirentide turns a small diagram DSL into clean, self-contained **SVG** — pure Java, zero
dependencies, safe to drop straight into a web page, no runtime JavaScript. New to it? See
**[QUICKSTART.md](QUICKSTART.md)** to get going and **[SLOWSTART.md](SLOWSTART.md)** for the why.

---

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
